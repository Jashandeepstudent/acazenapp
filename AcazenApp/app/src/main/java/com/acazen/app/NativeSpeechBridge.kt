package com.acazen.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import org.json.JSONObject

/** True for https pages on acazen.com (and its subdomains) — the only pages the app trusts. */
internal fun isAcazenUrl(url: String?): Boolean {
    val uri = try {
        Uri.parse(url ?: return false)
    } catch (e: Exception) {
        return false
    }
    val host = uri.host.orEmpty()
    return uri.scheme == "https" && (host == "acazen.com" || host.endsWith(".acazen.com"))
}

/**
 * Speech recognition for the website, done by the PHONE instead of the (missing) browser.
 *
 * Android's WebView has no working Web Speech API: any `webkitSpeechRecognition.start()` on the
 * site ends in a "not-allowed" error, which the site reports as "Microphone permission denied.
 * Please allow mic in browser settings" — even though the app's microphone permission is
 * already granted. So inside the app we replace `window.webkitSpeechRecognition` (see
 * [POLYFILL_JS]) with a thin object that talks to Android's own SpeechRecognizer through this
 * bridge. The website's existing voice mode then works unchanged: same events (start, result
 * with interim + final text, error, end), same restart-on-end behaviour.
 *
 * Only ever drives the microphone for acazen.com pages, only while the app is on screen, and
 * always through a real recognition service (never Acazen's own stub — see [Recognizers]).
 *
 * Every public @JavascriptInterface call arrives on a WebView thread, so each one hops to the
 * main thread before touching the SpeechRecognizer (which must live there).
 */
class NativeSpeechBridge(
    private val activity: MainActivity,
    private val webView: WebView
) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var sessionId = 0
    private var pendingEnd: Runnable? = null

    // ---- called from JavaScript ---------------------------------------------------------------

    @JavascriptInterface
    fun isAvailable(): Boolean = Recognizers.findService(activity) != null

    @JavascriptInterface
    fun start(id: Int, lang: String?, interim: Boolean) {
        main.post { startOnMain(id, lang, interim) }
    }

    @JavascriptInterface
    fun stop(id: Int) {
        main.post {
            if (id == sessionId) {
                try { recognizer?.stopListening() } catch (e: Exception) {}
            }
        }
    }

    @JavascriptInterface
    fun abort(id: Int) {
        main.post {
            if (id == sessionId) teardown()
            emit(id, "end")
        }
    }

    // ---- called by MainActivity ---------------------------------------------------------------

    /** Stop any listening (app left the screen / is closing) and tell the page it ended. */
    fun release() {
        val id = sessionId
        val wasActive = recognizer != null || pendingEnd != null
        teardown()
        if (wasActive) emit(id, "end")
    }

    // ---- main-thread internals ----------------------------------------------------------------

    private fun startOnMain(id: Int, lang: String?, interim: Boolean) {
        // A newer session supersedes whatever was running; its page object just gets an "end".
        val oldId = sessionId
        val hadOld = recognizer != null || pendingEnd != null
        teardown()
        if (hadOld) emit(oldId, "end")
        sessionId = id

        if (!isAcazenUrl(webView.url)) {
            endSoon(id, 400)
            return
        }
        // Don't open the microphone while the app is in the background; the site restarts
        // recognition after "end", so it simply resumes when the user comes back.
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            endSoon(id, 1000)
            return
        }
        activity.withMicPermission { granted ->
            if (id != sessionId) return@withMicPermission // superseded while the dialog was up
            if (granted) begin(id, lang, interim) else fail(id, "not-allowed")
        }
    }

    private fun begin(id: Int, lang: String?, interim: Boolean) {
        val sr = Recognizers.create(activity)
        if (sr == null) {
            fail(id, "service-not-allowed")
            return
        }
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            // Ignore anything from a recognizer that has since been replaced or torn down.
            private fun current() = id == sessionId && recognizer === sr

            override fun onReadyForSpeech(params: Bundle?) { if (current()) emit(id, "audiostart") }
            override fun onBeginningOfSpeech() { if (current()) emit(id, "speechstart") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if (current()) emit(id, "speechend") }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (!current()) return
                firstText(partialResults)?.let { emit(id, "result", resultJson(it, false)) }
            }

            override fun onResults(results: Bundle?) {
                if (!current()) return
                firstText(results)?.let { emit(id, "result", resultJson(it, true)) }
                teardown()
                emit(id, "end")
            }

            override fun onError(error: Int) {
                if (!current()) return
                fail(id, codeFor(error))
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interim)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (!lang.isNullOrBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        }
        try {
            sr.startListening(intent)
            emit(id, "start")
        } catch (e: Exception) {
            fail(id, "audio-capture")
        }
    }

    /** Report an error to the page, then end the session a moment later (never in a hot loop). */
    private fun fail(id: Int, code: String) {
        emit(id, "error", JSONObject().put("error", code))
        endSoon(id, 400)
    }

    private fun endSoon(id: Int, delayMs: Long) {
        teardown()
        val r = Runnable {
            pendingEnd = null
            emit(id, "end")
        }
        pendingEnd = r
        main.postDelayed(r, delayMs)
    }

    private fun teardown() {
        pendingEnd?.let { main.removeCallbacks(it) }
        pendingEnd = null
        val sr = recognizer
        recognizer = null
        if (sr != null) {
            try { sr.cancel() } catch (e: Exception) {}
            try { sr.destroy() } catch (e: Exception) {}
        }
    }

    private fun emit(id: Int, type: String, payload: JSONObject? = null) {
        val body = payload?.toString() ?: "null"
        val js = "window.__acazenNativeSTT&&window.__acazenNativeSTT.dispatch(" +
            id + "," + JSONObject.quote(type) + "," + body + ")"
        try {
            webView.evaluateJavascript(js, null)
        } catch (e: Exception) {
            // WebView already destroyed — nothing left to tell.
        }
    }

    private fun resultJson(text: String, final: Boolean) =
        JSONObject().put("text", text).put("final", final)

    private fun firstText(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** Android recognizer error → the Web Speech error name the site already handles. */
    private fun codeFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> "network"
        SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
        12, 13 -> "language-not-supported" // ERROR_LANGUAGE_NOT_SUPPORTED / _UNAVAILABLE (API 31+)
        else -> "aborted" // ERROR_CLIENT, RECOGNIZER_BUSY, …: the site just starts a fresh session
    }

    companion object {
        /**
         * Injected into every acazen.com page (idempotent). Defines window.SpeechRecognition /
         * window.webkitSpeechRecognition on top of the AcazenSTT bridge above. Does nothing
         * unless the bridge exists and the phone really has a recognition service.
         * Plain ES5 on purpose; contains no dollar signs (this is a Kotlin raw string).
         */
        val POLYFILL_JS: String = """
(function () {
  if (window.__acazenNativeSTT || !window.AcazenSTT) return;
  var bridge = window.AcazenSTT, available = false;
  try { available = !!bridge.isAvailable(); } catch (e) {}
  if (!available) return;

  var seq = 0, live = {};

  function Recognition() {
    this.lang = 'en-US';
    this.continuous = false;
    this.interimResults = false;
    this.maxAlternatives = 1;
    this.grammars = null;
    this._running = false;
    this._id = 0;
    this._listeners = {};
  }
  Recognition.prototype.addEventListener = function (type, fn) {
    (this._listeners[type] = this._listeners[type] || []).push(fn);
  };
  Recognition.prototype.removeEventListener = function (type, fn) {
    var list = this._listeners[type];
    if (!list) return;
    var i = list.indexOf(fn);
    if (i >= 0) list.splice(i, 1);
  };
  Recognition.prototype._emit = function (type, ev) {
    ev = ev || {};
    ev.type = type;
    ev.target = this;
    var h = this['on' + type];
    if (typeof h === 'function') { try { h.call(this, ev); } catch (e) { console.error(e); } }
    var list = this._listeners[type];
    if (list) list.slice().forEach(function (fn) {
      try { fn.call(this, ev); } catch (e) { console.error(e); }
    }, this);
  };
  Recognition.prototype.start = function () {
    if (this._running) {
      var err = new Error('recognition has already started');
      err.name = 'InvalidStateError';
      throw err;
    }
    this._running = true;
    this._id = ++seq;
    live[this._id] = this;
    bridge.start(this._id, String(this.lang || 'en-US'), !!this.interimResults);
  };
  Recognition.prototype.stop = function () { if (this._running) bridge.stop(this._id); };
  Recognition.prototype.abort = function () { if (this._running) bridge.abort(this._id); };

  window.__acazenNativeSTT = {
    dispatch: function (id, type, data) {
      var rec = live[id];
      if (!rec) return;
      if (type === 'result') {
        var alt = [{ transcript: data.text, confidence: data.final ? 0.9 : 0.5 }];
        alt.isFinal = !!data.final;
        alt.item = function (i) { return this[i]; };
        var results = [alt];
        results.item = function (i) { return this[i]; };
        rec._emit('result', { resultIndex: 0, results: results });
      } else if (type === 'error') {
        rec._emit('error', { error: data.error, message: '' });
      } else if (type === 'end') {
        delete live[id];
        rec._running = false;
        rec._emit('end');
      } else {
        rec._emit(type);
      }
    }
  };

  ['SpeechRecognition', 'webkitSpeechRecognition'].forEach(function (name) {
    try {
      Object.defineProperty(window, name, { value: Recognition, configurable: true, writable: true });
    } catch (e) { window[name] = Recognition; }
  });
})();
"""
    }
}
