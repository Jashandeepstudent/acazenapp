package com.acazen.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

/**
 * Rehearsal-mode overlay ("Try it" on the setup page) for the hold-Power voice shortcut — same
 * look and speech-recognition flow Acazen uses for the real thing, so the user can confirm mic +
 * recognizer work on their phone before ever relying on the gesture itself.
 *
 * The REAL gesture no longer launches this Activity: since Acazen became the device's
 * registered Assistant app (RoleManager.ROLE_ASSISTANT — see AssistantSetupActivity), the
 * system itself invokes AcazenInteractionSession directly for the hold-Power gesture, which
 * reuses this same UI/recognition logic hosted in a VoiceInteractionSession instead of an
 * Activity (a plain startActivity() from the background is blocked by Android's
 * background-activity-start policy on Android 10+, which is exactly why intercepting the key
 * and launching an Activity by hand never worked reliably — the assistant-role path sidesteps
 * that restriction entirely because the system does the launching).
 */
class VoiceCommandActivity : ComponentActivity() {

    companion object {
        const val ACTION_VOICE_COMMAND = "com.acazen.app.action.VOICE_COMMAND"
        const val EXTRA_VOICE_TEXT = "voice_text"

        // Test mode: launched from the Hold-Power setup page ("Try it right now") so the
        // user can rehearse the exact overlay before ever pressing the button. Same UI, same
        // recognizer — proves end-to-end that mic + speech service work on this device.
        const val ACTION_TEST = "com.acazen.app.action.DOUBLE_PRESS_TEST"

        /** Open the listening overlay in rehearsal mode (from the setup page's "Try it"). */
        fun launchTest(context: Context) {
            context.startActivity(
                Intent(context, VoiceCommandActivity::class.java).apply {
                    action = ACTION_TEST
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
            )
        }

        // How long we let one utterance run before forcing a send (safety net if the
        // recognizer never reports end-of-speech).
        private const val MAX_LISTEN_MS = 8000L
        private const val SENDING_HOLD_MS = 700L
    }

    private var recognizer: SpeechRecognizer? = null
    private var started = false
    private var sent = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var pulseDot: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Light the screen and draw over the keyguard immediately — this is the whole point
        // of launching from a power-hold while the phone sleeps.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(buildUi())

        val isTest = intent?.action == ACTION_TEST

        // If something launched us without wanting dictation, just forward to the app.
        if (!isTest && intent?.action != ACTION_VOICE_COMMAND) {
            handOff(text = null)
            return
        }

        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val recognizerAvailable = SpeechRecognizer.isRecognitionAvailable(this)

        when {
            !recognizerAvailable -> {
                if (isTest) showTestResult("✗ This phone has no built-in voice recognition. Install Google app or use another assistant-capable ROM.")
                else failGracefully("Voice recognition isn't available on this device")
            }
            !micGranted -> {
                if (isTest) {
                    showTestResult("✗ Microphone not allowed yet — tap step 1 on the setup page first.")
                } else {
                    // Route to the setup page so the user finishes granting permissions instead
                    // of seeing a dead-ended overlay with no way to dictate.
                    AssistantSetupActivity.launch(this)
                    finish()
                }
            }
            else -> beginListening()
        }
    }

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#B3000000")) // 70% scrim, assistant-overlay feel

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(26), dp(28), dp(22))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F7FFFFFF"))
                cornerRadius = dp(28).toFloat()
            }
        }

        pulseDot = TextView(this).apply {
            text = "●"
            textSize = 30f
            setTextColor(Color.parseColor("#1A73E8"))
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            text = "Listening… speak now"
            textSize = 17f
            setTextColor(Color.parseColor("#111111"))
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(10))
        }

        transcriptText = TextView(this).apply {
            text = ""
            textSize = 20f
            setTextColor(Color.parseColor("#222222"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))
        }

        val hint = TextView(this).apply {
            text = "It will be sent to Acazen automatically"
            textSize = 12f
            setTextColor(Color.parseColor("#777777"))
            gravity = Gravity.CENTER
        }

        val scroll = ScrollView(this).apply {
            addView(transcriptText)
            isVerticalScrollBarEnabled = false
        }

        card.addView(pulseDot)
        card.addView(statusText)
        card.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(96)
        ))
        card.addView(hint)

        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply { setMargins(dp(28), dp(28), dp(28), dp(28)) })

        startPulse()
        return root
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun startPulse() {
        pulseDot.animate().alpha(0.25f).setDuration(500).withEndAction {
            if (!sent && !isFinishing) {
                pulseDot.animate().alpha(1f).setDuration(500)
                    .withEndAction { if (!sent && !isFinishing) startPulse() }.start()
            }
        }.start()
    }

    private fun beginListening() {
        if (started || sent) return
        started = true

        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                statusText.text = "Listening…"
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                // Anything other than "you said nothing" → open the app anyway rather than
                // dead-ending the gesture. ERROR_NO_MATCH / ERROR_SPEAKER_TIMEOUT → gentle retry
                // once, then hand off empty.
                // ERROR_SPEAKER_TIMEOUT (7) referenced as a literal constant because it is
                // @hide in the public SDK and does not resolve at compile time.
                val noMatchOrTimeout = error == SpeechRecognizer.ERROR_NO_MATCH || error == 7
                if (noMatchOrTimeout && !retriedOnce) {
                    retriedOnce = true
                    try { sr.startListening(listeningIntent()) } catch (e: Exception) { handOff(null) }
                    return
                }
                handOff(text = null)
            }

            override fun onResults(results: Bundle?) {
                deliver(results)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                showLive(partialResults)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            sr.startListening(listeningIntent())
        } catch (e: Exception) {
            failGracefully("Couldn't start listening")
            return
        }

        // Safety net: force-send even if the recognizer never signals end-of-speech.
        mainHandler.postDelayed({ if (!sent) deliverLastKnown() }, MAX_LISTEN_MS)
    }

    private var retriedOnce = false
    private var lastHeard: String? = null

    private fun listeningIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun showLive(bundle: Bundle?) {
        val t = firstText(bundle) ?: return
        lastHeard = t
        transcriptText.text = t
        statusText.text = "I heard you…"
    }

    private fun deliver(bundle: Bundle?) {
        val t = firstText(bundle) ?: lastHeard
        handOff(text = t)
    }

    private fun deliverLastKnown() {
        try { recognizer?.cancel() } catch (e: Exception) {}
        handOff(text = lastHeard)
    }

    private fun firstText(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun failGracefully(reason: String) {
        statusText.text = reason
        mainHandler.postDelayed({ handOff(text = null) }, 1200)
    }

    private fun showTestResult(msg: String) {
        pulseDot.animate().cancel()
        statusText.text = msg
        transcriptText.text = ""
        mainHandler.postDelayed({ if (!isFinishing) finish() }, 2600)
    }

    /** Auto-send: open Acazen in voice mode carrying the spoken text, then vanish. */
    private fun handOff(text: String?) {
        if (sent) return
        sent = true
        pulseDot.animate().cancel()

        if (intent?.action == ACTION_TEST) {
            // Rehearsal mode: show what we heard right here so the user gets instant feedback.
            statusText.text = if (text != null) "✓ Heard: \"$text\" — the gesture works!" else "✗ Didn't catch that — try again from the setup page."
            mainHandler.postDelayed({ if (!isFinishing) finish() }, 2800)
            return
        }

        statusText.text = if (text != null) "Sending to Acazen…" else "Opening Acazen…"

        mainHandler.postDelayed({
            try {
                val main = Intent(this, MainActivity::class.java).apply {
                    // With text: voice-command handoff; without: plain wake handoff (the site
                    // still enters ?voice=1 mode either way — see MainActivity.homeUrlFor).
                    action = if (text != null) ACTION_VOICE_COMMAND else MainActivity.ACTION_WAKE_WORD
                    if (text != null) putExtra(EXTRA_VOICE_TEXT, text)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(main)
            } catch (e: Exception) {
                try {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                } catch (ignored: Exception) {}
            }
            finish()
            overridePendingTransition(0, android.R.anim.fade_out)
        }, SENDING_HOLD_MS)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try { recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
