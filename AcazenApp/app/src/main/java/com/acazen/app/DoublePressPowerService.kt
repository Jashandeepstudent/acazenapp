package com.acazen.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Safari-style wake shortcut: DOUBLE-PRESS the Power button, anywhere on the phone (screen on
 * OR off, even from the lock screen), and Acazen's listening UI pops up. Say your question —
 * it appears on screen as text — and it is AUTO-SENT to Acazen. No wake-word model needed for
 * this path, no mic running in the background all day, zero extra battery drain.
 *
 * How it works: an AccessibilityService is the only sanctioned way for a normal app to observe
 * hardware key events system-wide. We watch for two KEYCODE_POWER presses (down/up pairs) within
 * DOUBLE_PRESS_WINDOW_MS and then start VoiceCommandActivity, which draws itself over the
 * keyguard. The user must explicitly enable this service once in Settings (Android requires it —
 * we deep-link them there from the app's toggle; see MainActivity's AssistantBridge).
 *
 * Note on Android 9+: a plain double-press Power is also used by some OEMs for camera/assistant;
 * ours still fires because we observe the raw key events before any global handling completes.
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
            // Receive every event type so raw hardware key events reach onKeyEvent().
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
