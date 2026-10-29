package com.acazen.app

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
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

/**
 * Foreground service that keeps Vosk listening for the "Hey Acazen" wake word, any time the user
 * has the feature turned on — screen on or off, Acazen in the foreground, backgrounded, or not
 * running at all. Android requires a persistent notification for any app that listens to the
 * microphone outside its own foreground UI; that notification is this service's whole reason
 * for being a *foreground* service rather than a plain background one, which Android would kill
 * within seconds.
 *
 * Uses Vosk (Apache 2.0, fully offline, no account or key) rather than a dedicated low-power
 * wake-word engine: it's a full small-vocabulary speech recognizer running continuously, which
 * costs more battery than a purpose-built keyword spotter would, but needs no training pipeline,
 * no API key and no usage limits. The Recognizer is built with a constrained grammar — just
 * "hey acazen" plus a catch-all "[unk]" token — rather than open transcription, which keeps
 * accuracy and speed close to a real keyword spotter despite using a general STT model
 * underneath. See models/src/main/assets/model-en-us/README.md for how the model gets bundled.
 *
 * On detection, it launches MainActivity with HOME + "?voice=1" so the page starts listening
 * for the actual command the same way it would if the user had tapped the mic button themselves.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "hey_acazen_listening"
        private const val NOTIFICATION_ID = 4201
        private const val SAMPLE_RATE = 16000.0f

        const val ACTION_START = "com.acazen.app.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.acazen.app.action.STOP_WAKE_WORD"

        // Folder name inside assets/ — must match models/build.gradle's genUUID task and the
        // actual unzipped Vosk model folder contents (see models/.../model-en-us/README.md).
        const val MODEL_ASSET_NAME = "model-en-us"

        // Constrains the recognizer to just this phrase (plus "unknown" for everything else)
        // instead of open vocabulary transcription — faster and more accurate for a single
        // fixed wake phrase. Only works with lookahead models (the small model qualifies);
        // precompiled HCLG graph models do not support this.
        private const val WAKE_GRAMMAR = "[\"hey acazen\", \"[unk]\"]"
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var starting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Quiet in production — this runs continuously in the background, and Vosk/Kaldi's
        // INFO level logs on every utterance.
        LibVosk.setLogLevel(LogLevel.WARNINGS)
    }

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

        if (speechService != null || starting) return // already running or loading
        starting = true

        // Unpacking copies the model from assets/ to app storage on first run (or after an
        // update changes the bundled model's uuid file) — can take a moment for ~40MB, so it
        // runs on StorageService's own background executor and we pick up from its callback.
        StorageService.unpack(
            applicationContext,
            MODEL_ASSET_NAME,
            "model",
            { unpackedModel -> onModelReady(unpackedModel) },
            { exception ->
                Log.e(TAG, "Failed to unpack the Vosk model — is $MODEL_ASSET_NAME present in " +
                    "assets (see models/src/main/assets/model-en-us/README.md)? ${exception.message}", exception)
                starting = false
                WakeWordPrefs.setEnabled(this, false)
                stopSelf()
            }
        )
    }

    private fun onModelReady(unpackedModel: Model) {
        if (!WakeWordPrefs.isEnabled(this)) {
            // Turned off while the model was still unpacking.
            starting = false
            unpackedModel.close()
            stopSelf()
            return
        }

        try {
            model = unpackedModel
            val rec = Recognizer(unpackedModel, SAMPLE_RATE, WAKE_GRAMMAR)
            recognizer = rec
            val service = SpeechService(rec, SAMPLE_RATE)
            speechService = service
            service.startListening(listener)
            Log.i(TAG, "Hey Acazen is listening.")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start Vosk recognizer: ${e.message}", e)
            WakeWordPrefs.setEnabled(this, false)
            stopListening()
            stopSelf()
        } finally {
            starting = false
        }
    }

    // Vosk hands back raw Kaldi JSON, e.g. {"text": "hey acazen"} or {"partial": "hey ac"}.
    // We check both the partial stream (fires fastest, as soon as a few frames match — fires
    // detection the instant it's confident, without waiting for a pause in speech) and the
    // final per-utterance result, and only ever fire once per detection via triggerWakeWordOnce.
    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String) {
            checkForWakeWord(extractField(hypothesis, "partial"))
        }

        override fun onResult(hypothesis: String) {
            checkForWakeWord(extractField(hypothesis, "text"))
        }

        override fun onFinalResult(hypothesis: String) {
            checkForWakeWord(extractField(hypothesis, "text"))
        }

        override fun onError(exception: Exception) {
            Log.e(TAG, "Vosk recognition error: ${exception.message}", exception)
            // AudioRecord/mic errors are usually transient (another app briefly grabbed the
            // mic). Tear down and let the next ACTION_START (service restart, or the user
            // toggling it) try again rather than leaving a dead recognizer running silently.
            stopListening()
            stopSelf()
        }

        override fun onTimeout() {
            // We never pass a timeout to startListening(), so this shouldn't fire — but handle
            // it defensively rather than leaving the service in a stopped-but-alive state.
            stopListening()
            stopSelf()
        }
    }

    private fun extractField(json: String, field: String): String =
        try {
            JSONObject(json).optString(field, "")
        } catch (e: Exception) {
            ""
        }

    private var lastTriggerAt = 0L

    private fun checkForWakeWord(text: String) {
        val normalized = text.trim().lowercase()
        if (normalized.isEmpty() || !normalized.contains("hey acazen")) return

        // Debounce: a single utterance can produce several partial results that all contain
        // the phrase as the user keeps talking, and onResult/onFinalResult can also both fire
        // for the same utterance. Ignore re-triggers within a short window of the last one.
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < 3000L) return
        lastTriggerAt = now

        onWakeWordDetected()
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
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            // Best-effort cleanup.
        }
        speechService = null
        try {
            recognizer?.close()
        } catch (e: Exception) {
        }
        recognizer = null
        try {
            model?.close()
        } catch (e: Exception) {
        }
        model = null
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
