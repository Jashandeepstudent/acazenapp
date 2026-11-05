package com.acazen.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The real "hold Power → Acazen is listening" overlay, drawn as the content view of a
 * VoiceInteractionSession. The framework creates and shows this window for us whenever the
 * system's native assist gesture fires for Acazen — the power button hold included, now that
 * Acazen is registered as the device's Assistant (RoleManager.ROLE_ASSISTANT) rather than
 * trying to intercept the raw key itself.
 *
 * Lifecycle (confirmed against the AOSP VoiceInteractionSession source — showWindow() itself is
 * package-private and never callable from a subclass; the framework drives the whole sequence):
 * onCreate() → onCreateContentView() [UI only, no args yet] → onShow(args, showFlags) [args are
 * available here, right before the window is actually shown] → window appears.
 *
 * The UI and speech-recognition flow are carried over from the old VoiceCommandActivity
 * (same look, same auto-send-on-silence behavior); only the hosting mechanism changed, from an
 * Activity to a VoiceInteractionSession, because that is what the platform actually invokes for
 * this gesture.
 */
class AcazenInteractionSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val MAX_LISTEN_MS = 8000L
        private const val SENDING_HOLD_MS = 700L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var started = false
    private var sent = false
    private var retriedOnce = false
    private var lastHeard: String? = null

    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var pulseDot: TextView

    /**
     * Args aren't available yet in onCreate()/onCreateContentView() (see lifecycle note above),
     * so window flags and the actual listening start are applied here, right before the window
     * is shown. onShow() can run more than once per visible window, hence the `started`/`sent`
     * guards below — listening and hand-off only ever happen once per session instance.
     */
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Draw over the keyguard and keep the screen on — this is the whole point of a gesture
        // that can fire from a sleeping/locked phone. getWindow() returns the hosting Dialog;
        // .window on that is the real android.view.Window the WindowManager flags apply to.
        try {
            window?.window?.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        } catch (e: Exception) { /* non-fatal: worst case the screen doesn't light on its own */ }
        beginListening()
    }

    override fun onCreateContentView(): View {
        val root = FrameLayout(context)
        root.setBackgroundColor(Color.parseColor("#B3000000")) // 70% scrim, assistant-overlay feel

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(26), dp(28), dp(22))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F7FFFFFF"))
                cornerRadius = dp(28).toFloat()
            }
        }

        pulseDot = TextView(context).apply {
            text = "●"
            textSize = 30f
            setTextColor(Color.parseColor("#1A73E8"))
            gravity = Gravity.CENTER
        }

        statusText = TextView(context).apply {
            text = "Listening… speak now"
            textSize = 17f
            setTextColor(Color.parseColor("#111111"))
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(10))
        }

        transcriptText = TextView(context).apply {
            text = ""
            textSize = 20f
            setTextColor(Color.parseColor("#222222"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))
        }

        val hint = TextView(context).apply {
            text = "It will be sent to Acazen automatically"
            textSize = 12f
            setTextColor(Color.parseColor("#777777"))
            gravity = Gravity.CENTER
        }

        val scroll = ScrollView(context).apply {
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
        // Listening itself starts from onShow(), once args/window are available — see that
        // override's doc comment for why onCreateContentView() is too early for it.
        return root
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun startPulse() {
        pulseDot.animate().alpha(0.25f).setDuration(500).withEndAction {
            if (!sent) {
                pulseDot.animate().alpha(1f).setDuration(500)
                    .withEndAction { if (!sent) startPulse() }.start()
            }
        }.start()
    }

    private fun beginListening() {
        if (started || sent) return
        started = true

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            failGracefully("Voice recognition isn't available on this device")
            return
        }

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
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
                // ERROR_SPEAKER_TIMEOUT (7) is @hide in the public SDK; referenced as a literal.
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

        mainHandler.postDelayed({ if (!sent) deliverLastKnown() }, MAX_LISTEN_MS)
    }

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

    /** Auto-send: open Acazen in voice mode carrying the spoken text, then close the session. */
    private fun handOff(text: String?) {
        if (sent) return
        sent = true
        pulseDot.animate().cancel()
        statusText.text = if (text != null) "Sending to Acazen…" else "Opening Acazen…"

        mainHandler.postDelayed({
            try {
                val main = Intent(context, MainActivity::class.java).apply {
                    action = if (text != null) VoiceCommandActivity.ACTION_VOICE_COMMAND
                    else MainActivity.ACTION_WAKE_WORD
                    if (text != null) putExtra(VoiceCommandActivity.EXTRA_VOICE_TEXT, text)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(main)
            } catch (e: Exception) {
                try {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                } catch (ignored: Exception) {}
            }
            hide()
        }, SENDING_HOLD_MS)
    }

    override fun onHide() {
        mainHandler.removeCallbacksAndMessages(null)
        try { recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
        super.onHide()
    }
}
