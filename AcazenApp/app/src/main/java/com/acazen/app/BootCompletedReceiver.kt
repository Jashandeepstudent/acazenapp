package com.acazen.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Restarts the "Hey Acazen" foreground service after the phone reboots, but only if the user
 * had actually turned the feature on before — a fresh install or an unused toggle should never
 * start listening on its own just because the phone restarted.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!WakeWordPrefs.isEnabled(context)) return

        val serviceIntent = Intent(context, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_START
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
