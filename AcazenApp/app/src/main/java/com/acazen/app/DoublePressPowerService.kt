package com.acazen.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Siri-style wake shortcut: DOUBLE-PRESS the Power button anywhere on the phone (screen on OR
 * off, even from the lock screen) and Acazen's listening UI pops up. Say your question — it
 * appears on screen as text — and it is AUTO-SENT to Acazen. There is no wake word anymore:
 * this gesture is the only system-level way a normal Android app can summon its own voice UI
 * from anywhere, which is exactly how Samsung's "double press Home" for Bixby works.
 *
 * ── Why the previous version opened the wallpaper instead of Acazen ──────────────────────
 * Two real bugs, both fixed here:
 *
 *  1. onKeyEvent() was NEVER called. Android only delivers raw hardware key events to an
 *     accessibility service when the service sets the FLAG_REQUEST_FILTER_KEY_EVENTS flag at
 *     runtime (or declares it in its xml config). The old code requested neither, so the
 *     double-press detection never ran at all.
 *
 *  2. Even if it had fired, VoiceCommandActivity launched with plain startActivity() from a
 *     background context. Since Android 10 that is blocked by the background-activity-start
 *     policy — the launch was silently dropped, and what the user saw was simply whatever the
 *     power presses did on their own (screen off → the wallpaper/lock screen came back on).
 *
 * Fixes: request the key-filter flag; and reach the overlay through a notification issued with
 * setFullScreenIntent(), which is one of the few sanctioned ways to bring an activity to the
 * foreground from the background — including over the keyguard, since the activity declares
 * showWhenLocked + turnScreenOn.
 */
class DoublePressPowerService : AccessibilityService() {

    companion object {
        const val DOUBLE_PRESS_WINDOW_MS = 400L

        private var instance: DoublePressPowerService? = null

        /** True when the accessibility service is enabled AND running. */
        fun isRunning(): Boolean = instance != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastPowerUpAt = 0L
    private var pendingSecondPressTimeout: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            // THE critical line: without FLAG_REQUEST_FILTER_KEY_EVENTS the framework never
            // calls onKeyEvent() and the whole feature is dead (this was the original bug).
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            // Key events don't need window-state reads; keep the footprint minimal.
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_POWER) return false

        val now = System.currentTimeMillis()

        if (event.action == KeyEvent.ACTION_UP) {
            val sinceLast = now - lastPowerUpAt
            if (lastPowerUpAt > 0 && sinceLast <= DOUBLE_PRESS_WINDOW_MS) {
                // Two clean power presses inside the window: fire the wake UI.
                lastPowerUpAt = 0L
                cancelPendingTimeout()
                triggerWake()
                // Consume the second press so the system doesn't also treat it as its own
                // short-press action while our activity is coming up.
                return true
            }
            lastPowerUpAt = now
            // If no matching second press arrives, forget the first one.
            cancelPendingTimeout()
            pendingSecondPressTimeout = Runnable { lastPowerUpAt = 0L }.also {
                mainHandler.postDelayed(it, DOUBLE_PRESS_WINDOW_MS + 50)
            }
        }
        return false
    }

    private fun cancelPendingTimeout() {
        pendingSecondPressTimeout?.let { mainHandler.removeCallbacks(it) }
        pendingSecondPressTimeout = null
    }

    private fun triggerWake() {
        try {
            VoiceCommandActivity.launchFromDoublePress(this)
        } catch (e: Exception) {
            // Accessibility services can be killed mid-launch under memory pressure; nothing
            // user-visible to do — the next double-press simply tries again.
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed — we only care about key events. Required abstract method.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        cancelPendingTimeout()
        super.onDestroy()
    }
}
