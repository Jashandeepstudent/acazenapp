package com.acazen.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Siri-style wake shortcut: HOLD the Power button (~0.5s) anywhere on the phone — screen off,
 * lock screen, home, any app — and Acazen's listening UI pops up over everything. Say your
 * question; it appears as live text and is AUTO-SENT to Acazen. There is no wake word anymore.
 *
 * ── Why we moved from DOUBLE-PRESS to HOLD (the "wallpaper bug") ──────────────────────────
 * On real devices (Pixel, Samsung One UI, Xiaomi/HyperOS…) Android routes the power-key gesture
 * itself before any accessibility service can react:
 *   • a short press  → screen off/on (shows the wallpaper/lock screen),
 *   • a double press → OEM-assigned action (camera / Assistant / wallpaper picker),
 *   • a long press   → the power menu, which is DISMISSED by an accessibility service that
 *     consumes the key events — exactly what we do here.
 * Double-press therefore always lost the race with the system gesture and the user saw the
 * wallpaper instead of Acazen. Holding the key is the ONLY power-gesture an accessibility
 * service can reliably intercept AND cancel (we swallow DOWN/UP for the whole hold, so neither
 * the screen toggle nor the power menu ever fires). This is also how Samsung lets you summon
 * Bixby ("hold Home"), so it feels native.
 *
 * Detection rules (all inside onKeyEvent, which only runs when
 * FLAG_REQUEST_FILTER_KEY_EVENTS is set — set both at runtime below AND in the xml config):
 *   • Press shorter than CANCEL_MS (<180ms) is ignored entirely (screen off/on taps pass
 *     through untouched — we never fight the normal power button).
 *   • Press held >= HOLD_TRIGGER_MS (500ms) with all events consumed → launch the overlay.
 *   • Release before the threshold but after CANCEL_MS → the events were already consumed, so
 *     nothing happens at all (no accidental screen-off, no power menu). Clean and predictable.
 */
class DoublePressPowerService : AccessibilityService() {

    companion object {
        /** How long the Power key must be held to summon Acazen. Comfortably past a normal
         *  screen-off tap, comfortably before Google's Assistant hold (~800ms+). */
        const val HOLD_TRIGGER_MS = 500L

        /** Presses shorter than this are treated as ordinary screen off/on and passed through. */
        const val CANCEL_MS = 180L

        private var instance: DoublePressPowerService? = null

        /** True when the accessibility service is enabled AND bound. */
        fun isRunning(): Boolean = instance != null

        /** Diagnostic: did onKeyEvent() EVER fire since boot? Surfaced on the setup page. */
        @Volatile var sawAnyPowerKey: Boolean = false
            private set

        /** Diagnostic: last time a hold was successfully detected (for the setup page test row). */
        @Volatile var lastHoldAt: Long = 0L
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // State machine for one physical press of the Power key.
    private var downAt = 0L
    private var holding = false          // DOWN seen, not yet released
    private var consumedWholePress = false // every event of this press swallowed (hold fired)
    private var holdTimeoutRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            // THE critical line: without FLAG_REQUEST_FILTER_KEY_EVENTS the framework never
            // calls onKeyEvent() and the whole feature is dead. Also declared in
            // res/xml/accessibility_double_press.xml for OEMs that read config at bind time.
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_POWER) return false
        sawAnyPowerKey = true

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (holding) return true // auto-repeat while held: swallow
                holding = true
                consumedWholePress = false
                downAt = System.currentTimeMillis()
                // Schedule the hold trigger. Because we will consume the matching UP too, the
                // system sees NO complete click and NO long-press → no wallpaper, no power menu.
                cancelHoldTimeout()
                holdTimeoutRunnable = Runnable {
                    if (holding && System.currentTimeMillis() - downAt >= HOLD_TRIGGER_MS - 20) {
                        consumedWholePress = true
                        lastHoldAt = System.currentTimeMillis()
                        DoublePressPrefs.setLastTrigger(this, lastHoldAt)
                        triggerWake()
                    }
                }.also { mainHandler.postDelayed(it, HOLD_TRIGGER_MS) }
                // Consume the DOWN immediately: prevents the screen-off animation from starting
                // under us mid-hold (this was the exact "wallpaper" complaint).
                return true
            }

            KeyEvent.ACTION_UP -> {
                cancelHoldTimeout()
                val heldFor = System.currentTimeMillis() - downAt
                holding = false
                return when {
                    consumedWholePress -> {
                        // We already launched the overlay during this hold; swallow the UP so
                        // the system doesn't also register a release of a long press.
                        true
                    }
                    heldFor < CANCEL_MS -> {
                        // Ordinary quick tap (screen off/on): let BOTH its events through
                        // untouched. (Its DOWN was consumed above, so replay nothing special —
                        // Android still toggles the screen on the UP for filtered keys.)
                        false
                    }
                    else -> {
                        // Released between CANCEL and HOLD thresholds: keep swallowing so no
                        // stray power-menu/screen-toggle fires. Next press starts fresh.
                        true
                    }
                }
            }
        }
        return false
    }

    private fun cancelHoldTimeout() {
        holdTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        holdTimeoutRunnable = null
    }

    private fun triggerWake() {
        try {
            // Light the screen first so the overlay draws instantly even from deep doze.
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ON_AFTER_RELEASE or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Acazen:wake-powerhold"
            )
            wl.acquire(3500L)
        } catch (e: Exception) { /* non-fatal */ }
        try {
            VoiceCommandActivity.launchFromDoublePress(this)
        } catch (e: Exception) {
            // Service can be killed mid-launch under memory pressure; next hold simply retries.
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed — we only care about key events. Required abstract method.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        cancelHoldTimeout()
        super.onDestroy()
    }
}
