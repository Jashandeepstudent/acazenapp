package com.acazen.app

import android.content.Context

/**
 * Whether the user has turned "Hey Acazen" on. Read by:
 *  - MainActivity's AcazenAssistant JS bridge (to answer isEnabled() and to decide
 *    whether to start the service from onCreate)
 *  - BootCompletedReceiver (to decide whether to restart the service after reboot)
 *  - WakeWordService itself (so it can refuse to start if the user turned it off
 *    between the broadcast being sent and the service actually starting)
 */
object WakeWordPrefs {
    private const val PREFS_NAME = "wake_word_prefs"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
