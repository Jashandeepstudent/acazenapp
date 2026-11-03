package com.acazen.app

import android.app.KeyguardManager
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
 * wake-word engine: it's a full speech recognizer running continuously, which costs more battery
 * than a purpose-built keyword spotter would, but needs no training pipeline, no API key and no
 * usage limits. See models/src/main/assets/model-en-us/README.md for how the model gets bundled.
 *
 * IMPORTANT — why this uses OPEN-VOCABULARY recognition + fuzzy matching instead of a Vosk
 * "constrained grammar": the small model's word list does not contain "acazen" at all, so a
 * grammar like ["hey acazen", "[unk]"] can never produce that literal text — every utterance
 * collapses to [unk] and the wake word NEVER fires. Instead we transcribe freely and match what
 * Vosk actually hears ("hey acazen", "hey acason", "hey a casen", "hey ocean", ...) against
 * phonetic variants of the wake phrase via Levenshtein distance. This also makes the feature
 * resilient to near-miss pronunciations, which was the other half of "it do not work".
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

        // The recognizer runs in OPEN-vocabulary mode (no grammar string) on purpose. A
        // constrained grammar only works for words that exist in the model's vocabulary, and
        // "acazen" is a made-up brand name that isn't in vosk-model-small-en-us-0.15's word
        // list — with a ["hey acazen", "[unk]"] grammar every utterance decoded to [unk] and
        // the wake word could never fire. Open transcription + the fuzzy matcher below is what
        // actually makes "Hey Acazen" work.
        private const val MAX_WORDS_PER_SEGMENT = 6

        // Phonetic spellings of "hey acazen" as an English ASR model realistically hears the
        // brand name ("ak-a-zen"): hey acason / a casen / ack a zen / hey ocean etc. Matched
        // with Levenshtein distance so small mis-hearings still wake the app. Extend this list
        // any time users report a pronunciation that doesn't trigger.
        private val WAKE_VARIANTS = listOf(
            "heiacazen", "heiacazon", "heiacasen", "heiacasin", "heiaczan",
            "heiacacen", "heiacaczen", "heiacoken", "heiokan", "heiacan",
            "heiacizen", "heiacuzzin", "heiacozon", "heyacazen", "heiacazn",
            "heiazen", "heiaczn", "hiazen", "eyaazen", "aeiazzen"
        )

        // How many character edits we tolerate against the variants above. Scales down for
        // short segments so two unrelated short words can't accidentally match.
        private fun allowedDistance(segmentLength: Int): Int = when {
            segmentLength <= 5 -> 1
            segmentLength <= 9 -> 2
            else -> 3
        }
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
            // Open-vocabulary recognition (no grammar arg) — see the companion object note: a
            // constrained grammar can never emit "acazen" because the word isn't in the model's
            // vocabulary, which is why the old build never fired.
            val rec = Recognizer(unpackedModel, SAMPLE_RATE)
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
    // final per-utterance result, and only ever fire once per detection via the debounce in
    // checkForWakeWord(). Note: SpeechService.startListening() (no timeout) keeps this
    // recognizer running indefinitely; results arrive whenever an endpoint is reached.
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
        if (!matchesWakeWord(text)) return

        // Debounce: a single utterance can produce several partial results that all contain
        // the phrase as the user keeps talking, and onResult/onFinalResult can also both fire
        // for the same utterance. Ignore re-triggers within a short window of the last one.
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < 3000L) return
        lastTriggerAt = now

        onWakeWordDetected()
    }

    // Fuzzy wake-word match against what Vosk actually transcribed. The old code required the
    // literal substring "hey acazen", which the open-vocab model almost never emits for a
    // made-up brand name — so nothing ever fired. Now we slide windows of 1..MAX_WORDS_PER_SEGMENT
    // consecutive words over the transcript, squash them into one lowercase letter-only string,
    // and accept it if its Levenshtein distance to any phonetic variant is within tolerance.
    internal fun matchesWakeWord(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("[^a-z ]"), " ")
        val words = normalized.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return false

        val maxWindow = minOf(MAX_WORDS_PER_SEGMENT, words.size)
        for (size in 2..maxWindow) {
            for (start in 0..words.size - size) {
                val segment = words.subList(start, start + size).joinToString("")
                val tolerance = allowedDistance(segment.length)
                for (variant in WAKE_VARIANTS) {
                    if (kotlin.math.abs(variant.length - segment.length) > tolerance) continue
                    if (levenshteinAtMost(segment, variant, tolerance)) {
                        Log.i(TAG, "Wake word matched transcript segment \"$segment\"")
                        return true
                    }
                }
            }
        }
        return false
    }

    // Bounded Levenshtein: returns true iff editDistance(a, b) <= maxDist, early-exiting as
    // soon as it's provable. Allocates two small int rows per call — negligible even though
    // this runs on every partial result, because the strings are a handful of characters long.
    private fun levenshteinAtMost(a: String, b: String, maxDist: Int): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > maxDist) return false
        var prev = IntArray(b.length + 1) { it }       // row i-1
        for (i in 1..a.length) {
            val curr = IntArray(b.length + 1)
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > maxDist) return false
            prev = curr
        }
        return prev[b.length] <= maxDist
    }

    private fun onWakeWordDetected() {
        Log.i(TAG, "\"Hey Acazen\" detected — waking the app.")

        // Turn the screen on (and dismiss an insecure keyguard) before launching. Without this,
        // a startActivity() from a background service while the screen is off is silently
        // dropped by Android and the app never opens — a big part of "it do not work when the
        // app is closed". With a PIN/pattern/password lock we can't bypass it (no permission to
        // request that), but the launch below still queues and Acazen comes up right after the
        // user unlocks.
        try {
            val km = getSystemService(KeyguardManager::class.java)
            if (km != null && !km.isKeyguardSecure) {
                @Suppress("DEPRECATION")
                km.newKeyguardLock("WakeWordService").disableKeyguard()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Keyguard hop skipped: ${e.message}")
        }

        val wake = Intent(this, WakeLaunchActivity::class.java).apply {
            action = MainActivity.ACTION_WAKE_WORD
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_FROM_BACKGROUND
        }
        try {
            startActivity(wake)
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch the wake activity", e)
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
