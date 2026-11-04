package com.acazen.app

import android.content.Context
import android.provider.Settings

/**
 * Tiny SharedPreferences store for the "double-press Power" shortcut (Safari-style assistive
 * wake). Separate file from WakeWordPrefs so toggling one feature never touches the other.
 */
object DoublePressPrefs {
    private const val PREFS = "acazen_double_press"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_TRIGGER = "last_trigger_at"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    /**
     * Single source of truth for "is the shortcut live?": the accessibility service is actually
     * enabled in system settings. Reading Settings.Secure (instead of only the running-instance
     * pointer, which lags while Android rebinds the service) is what fixes the deep bug where an
     * ON feature showed as OFF and the web toggle looked like it "never saved".
     */
    /** Timestamp of the last successful double-press detection (0 if never). */
    fun lastTriggerAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_TRIGGER, 0L)

    fun setLastTrigger(context: Context, at: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_TRIGGER, at)
            .apply()
    }

    fun isServiceEnabledInSettings(context: Context): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val suffix = "${context.packageName}/.DoublePressPowerService"
            enabled.split(':').any { it.isNotEmpty() && it.endsWith(suffix) }
        } catch (e: Exception) {
            false
        }
    }
}
