package com.acazen.app

/**
 * Tiny SharedPreferences mirror of "is the hold-Power shortcut live?", read by the website
 * bridge in MainActivity. Kept under its original file/object name (DoublePressPrefs) for
 * back-compat with existing callers — what changed is WHAT makes the shortcut live: it used to
 * be an AccessibilityService toggle, now it is Acazen holding RoleManager.ROLE_ASSISTANT (see
 * AssistantSetupActivity). The true source of truth is always RoleManager.isRoleHeld(), read
 * fresh each time; this pref is only a fast local mirror for the web UI.
 */
object DoublePressPrefs {
    private const val PREFS = "acazen_double_press"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_GESTURE_CONFIRMED = "gesture_confirmed"

    fun isEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /**
     * Android gives apps no way to read the phone's "hold Power for assistant" switch, so the
     * setup page asks the user to confirm they turned it on and remembers the answer here.
     */
    fun isGestureConfirmed(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_GESTURE_CONFIRMED, false)

    fun setGestureConfirmed(context: android.content.Context, confirmed: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GESTURE_CONFIRMED, confirmed)
            .apply()
    }

    fun setEnabled(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
