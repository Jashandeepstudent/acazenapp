package com.acazen.app

import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps Porcupine listening for the "Hey Acazen" wake word, any time
 * the user has the feature turned on — screen on or off, Acazen in the foreground, backgrounded,
 * or not running at all. Android requires a persistent notification for any app that listens to
 * the microphone outside its own foreground UI; that notification is this service's whole reason
 * for being a *foreground* service rather than a plain background one, which Android would kill
 * within seconds.
 *
 * On detection, it launches MainActivity with HOME + "?voice=1" so the page starts listening
 * for the actual command the same way it would if the user had tapped the mic button themselves.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "hey_acazen_listening"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_START = "com.acazen.app.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.acazen.app.action.STOP_WAKE_WORD"

        // Path inside assets/ to the trained "Hey Acazen" keyword file. Drop the file Porcupine
        // Console generates for "Hey Acazen" (Android platform) at this exact path. Until it's
        // there, the service logs a clear error and stops itself rather than crashing — the
        // AcazenAssistant bridge also checks for the file so the web UI can say so up front.
        const val KEYWORD_ASSET_PATH = "porcupine/hey_acazen_android.ppn"
    }

    private var porcupineManager: PorcupineManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startListening()
        }
        // Sticky: if Android kills this service under memory pressure, it restarts it (with a
        // null intent) as long as the user still has the feature enabled — checked in
        // startListening() itself, so a stale restart after the user disabled it is a no-op.
        return START_STICKY
    }

    private fun startListening() {
        if (!WakeWordPrefs.isEnabled(this)) {
            stopSelf()
            return
        }

        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(), foregroundServiceType())

        if (porcupineManager != null) return // already running

        val accessKey = BuildConfig.PORCUPINE_ACCESS_KEY
        if (accessKey.isBlank()) {
            Log.e(TAG, "PORCUPINE_ACCESS_KEY is empty — this build was not compiled with the " +
                "PORCUPINE_KEY environment variable set. Hey Acazen cannot start.")
            WakeWordPrefs.setEnabled(this, false)
            stopSelf()
            return
        }

        val callback = object : PorcupineManagerCallback {
            override fun invoke(keywordIndex: Int) {
                onWakeWordDetected()
            }
        }

        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPaths(arrayOf(KEYWORD_ASSET_PATH))
                .setSensitivities(floatArrayOf(0.6f))
                .build(applicationContext, callback)
            porcupineManager?.start()
            Log.i(TAG, "Hey Acazen is listening.")
        } catch (e: PorcupineException) {
            // Most commonly: the .ppn file isn't in assets yet, or the AccessKey is invalid/out
            // of free-tier quota. Either way, don't leave a broken foreground service running.
            Log.e(TAG, "Failed to start Porcupine — is $KEYWORD_ASSET_PATH present in assets? ${e.message}", e)
            WakeWordPrefs.setEnabled(this, false)
            stopSelf()
        }
    }

    private fun onWakeWordDetected() {
        Log.i(TAG, "\"Hey Acazen\" detected — waking the app.")
        val launch = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_WAKE_WORD
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        try {
            startActivity(launch)
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch MainActivity from wake word", e)
        }
    }

    private fun stopListening() {
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
        } catch (e: Exception) {
            // Best-effort cleanup.
        }
        porcupineManager = null
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    // ---- notification ---------------------------------------------------------------------

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hey Acazen",
            NotificationManager.IMPORTANCE_LOW // no sound/heads-up — this runs continuously
        ).apply {
            description = "Shown while Acazen is listening for \"Hey Acazen\"."
            setShowBadge(false)
            lightColor = Color.parseColor("#1A73E8")
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setColor(Color.parseColor("#1A73E8"))
            .setContentTitle("Listening for \"Hey Acazen\"")
            .setContentText("Tap to open Acazen, or turn this off in the app.")
            .setContentIntent(openPendingIntent)
            .addAction(0, "Turn off", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
}
