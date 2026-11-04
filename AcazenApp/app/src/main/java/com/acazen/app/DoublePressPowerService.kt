package com.acazen.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Siri-style wake shortcut: HOLD the Power button (~0.6s) anywhere on the phone — screen off,
 * lock screen, home, any app — and Acazen's listening UI pops up over everything. Say your
 * question; it appears as live text and is AUTO-SENT to Acazen. There is no wake word anymore.
 *
 * ── Redmi 9 / MIUI deep-dive (why "hold power" still showed the power menu before) ─────────
 * On MIUI/HyperOS (Redmi 9 included) the power-menu (Hold/Slide/Reboot) is shown by the SYSTEM
 * as soon as the framework decides a long press happened — driven by ViewConfiguration
 * .getLongPressTimeout() (~400–500ms), NOT by our accessibility service. The old build triggered
 * at exactly 500ms, i.e. AT the system threshold → race → power menu wins. Two hard facts about
 * the accessibility key pipeline explain everything else we now handle:
 *   • FLAG_REQUEST_FILTER_KEY_EVENTS must be active or onKeyEvent() is NEVER called. MIUI has
 *     shipped builds where the flag only takes effect if re-declared at runtime after bind — so
 *     we set it in BOTH places (onServiceConnected + xml config) AND self-heal: if the device is
 *     unlocked and we have seen ZERO power keys for a long time, we assume the flag was dropped,
 *     re-post serviceInfo, and surface that state ("MIUI blocked the gesture") on the setup page.
 *   • Once we consume the DOWN of a long press, some MIUI builds still synthesize their own
 *     long-press intent from the raw input path. We therefore ALSO cancel any pending power-menu
 *     via performGlobalAction(GLOBAL_ACTION_CANCEL) right when we fire, and keep swallowing the
 *     matching UP. Result: hold = Acazen only; quick tap (<180ms) = normal screen off/on.
 *
 * Detection rules (all inside onKeyEvent):
 *   • < CANCEL_MS (180ms): passed through untouched — ordinary screen off/on always works.
 *   • >= HOLD_TRIGGER_MS (600ms, safely past MIUI's ~400–500ms long-press threshold): launch
 *     overlay, swallow every event of the press, cancel the system power menu.
 *   • Released between thresholds: nothing happens (events already consumed). Predictable.
 */
class DoublePressPowerService : AccessibilityService() {

    companion object {
        /** Hold length that summons Acazen. Must stay ABOVE the OEM long-press/power-menu
         *  threshold (~400–500ms on MIUI) so we win the race deterministically. */
        const val HOLD_TRIGGER_MS = 600L

        /** Presses shorter than this are treated as ordinary screen off/on and passed through. */
        const val CANCEL_MS = 180L

        /** If the phone is unlocked and NO power key reached us for this long, MIUI probably
         *  dropped our key-filter flag at bind time → self-heal by re-posting serviceInfo. */
        const val STALE_REBIND_MS = 30_000L

        private var instance: DoublePressPowerService? = null

        /** True when the accessibility service is enabled AND bound. */
        fun isRunning(): Boolean = instance != null

        /** Diagnostic: did onKeyEvent() EVER fire since boot? Surfaced on the setup page. */
        @Volatile var sawAnyPowerKey: Boolean = false
            private set

        /** Diagnostic: last time a hold was successfully detected (setup-page test row). */
        @Volatile var lastHoldAt: Long = 0L
            private set

        /** Diagnostic: true once we've had to re-post serviceInfo because MIUI dropped the flag. */
        @Volatile var didSelfHeal: Boolean = false
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // State machine for one physical press of the Power key.
    private var downAt = 0L
    private var holding = false
    private var consumedWholePress = false
    private var holdTimeoutRunnable: Runnable? = null
    private var lastPowerKeyAt = 0L

    override fun onServiceConnected() {
        instance = this
        applyServiceInfo()
        super.onServiceConnected()
    }

    /** Declared BOTH here and in res/xml/accessibility_double_press.xml — MIUI is inconsistent
     *  about which source it honors; doing both is the documented community fix. */
    private fun applyServiceInfo() {
        try {
            serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                notificationTimeout = 100
            }
        } catch (e: Exception) { /* non-fatal */ }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_POWER) return false
        sawAnyPowerKey = true
        lastPowerKeyAt = System.currentTimeMillis()

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (holding) return true // auto-repeat while held: swallow
                holding = true
                consumedWholePress = false
                downAt = System.currentTimeMillis()
                cancelHoldTimeout()
                holdTimeoutRunnable = Runnable {
                    if (holding && System.currentTimeMillis() - downAt >= HOLD_TRIGGER_MS - 20) {
                        consumedWholePress = true
                        lastHoldAt = System.currentTimeMillis()
                        DoublePressPrefs.setLastTrigger(this, lastHoldAt)
                        triggerWake()
                    }
                }.also { mainHandler.postDelayed(it, HOLD_TRIGGER_MS) }
                // Consume the DOWN immediately: stops the screen-off animation starting mid-hold.
                return true
            }

            KeyEvent.ACTION_UP -> {
                cancelHoldTimeout()
                val heldFor = System.currentTimeMillis() - downAt
                holding = false
                return when {
                    consumedWholePress -> true   // swallow UP of the completed hold
                    heldFor < CANCEL_MS -> false // quick tap passes through: normal screen off/on
                    else -> true                 // released mid-window: swallow, do nothing
                }
            }
        }
        return false
    }

    /** Self-heal watchdog: if the phone is UNLOCKED and actively used but no POWER key ever
     *  reached onKeyEvent (or none for a long stretch), MIUI most likely dropped our key-filter
     *  flag at bind time. Re-post serviceInfo so the framework re-reads it. Cheap: debounced to
     *  at most once per window-change burst. */
    private val rebindCheck = object : Runnable {
        override fun run() {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                val awake = pm.isInteractive
                val stale = System.currentTimeMillis() - lastPowerKeyAt > STALE_REBIND_MS
                val neverSeen = !sawAnyPowerKey
                if (awake && (neverSeen || stale)) {
                    didSelfHeal = true
                    applyServiceInfo()
                }
            } catch (e: Exception) { /* non-fatal */ }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Watch for the user unlocking / waking the device, then verify our key filter is alive.
        val t = event?.eventType ?: return
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            t == AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) {
            mainHandler.removeCallbacks(rebindCheck)
            mainHandler.postDelayed(rebindCheck, 2500L)
        }
    }

    private fun cancelHoldTimeout() {
        holdTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        holdTimeoutRunnable = null
    }

    private fun triggerWake() {
        // Kill any half-open system power menu BEFORE drawing our overlay (MIUI race fix #2).
        try { performGlobalAction(GLOBAL_ACTION_CANCEL) } catch (e: Exception) { /* ok */ }
        try {
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

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        cancelHoldTimeout()
        mainHandler.removeCallbacks(rebindCheck)
        super.onDestroy()
    }
}
