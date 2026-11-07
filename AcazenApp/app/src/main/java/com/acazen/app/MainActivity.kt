package com.acazen.app

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import com.airbnb.lottie.LottieAnimationView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File

// Change this to point the app at a different site.
private const val HOME = "https://acazen.com"

// The website checks for this token in the user agent to know it is running inside the app
// (it uses it to skip intro.html).
private const val APP_UA = "AcazenApp/1.0"

private const val OFFLINE_PAGE = "file:///android_asset/offline.html"

// The opening animation stays at least this long, and never longer than the max.
private const val SPLASH_MIN_MS = 2200L
private const val SPLASH_MAX_MS = 7000L

// How long the offline game's victory animation gets before we swap back to the real
// site, in case it can't be reached for some reason. Matches offline.html's leaveDur.
private const val LEAVE_ANIM_MS = 750L

class MainActivity : AppCompatActivity() {

    companion object {
        // Legacy action kept for deep-link/back-compat; the wake word feature was removed in
        // 2.12.0 in favour of the hold-Power voice shortcut (VoiceCommandActivity).
        const val ACTION_WAKE_WORD = "com.acazen.app.action.WAKE_WORD"

        // The website reads this from the URL once (and the app strips it right after) to
        // auto-send a question captured by the hold-Power voice overlay.
        private const val VOICE_TEXT_PARAM = "voice_text"
    }

    private lateinit var webView: WebView
    private lateinit var splash: View
    private lateinit var offline: FrameLayout
    private var gameView: WebView? = null
    private val pulses = mutableListOf<ObjectAnimator>()
    private var failedUrl: String? = null
    private var offlineShown = false
    private var leavingOffline = false
    private var offlineMode = false   // true = no connection, false = connected but the site failed
    private var splashMinDone = false
    private var pageReady = false
    private var splashGone = false
    private var loaderAnim: ObjectAnimator? = null
    private var pendingWebPermission: PermissionRequest? = null
    private lateinit var speechBridge: NativeSpeechBridge
    private val micCallbacks = mutableListOf<(Boolean) -> Unit>()

    // File uploads from the website (photo / PDF / document pickers).
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var chooserParams: WebChromeClient.FileChooserParams? = null
    private var capturedUri: Uri? = null

    // Photo / video / PDF / document pickers for the website's upload buttons.
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            finishChooser(FileChooserParams.parseResult(result.resultCode, result.data))
        }

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            finishChooser(if (saved) capturedUri?.let { arrayOf(it) } else null)
        }

    private val capturePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCapture() else openPicker()
        }

    // Camera / microphone access for the website (Smart focus, voice mode, recordings).
    // These use the app's own Android permissions — Android asks the user once, and the
    // site never shows a browser-style permission prompt inside the app.
    private val webPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val request = pendingWebPermission
            pendingWebPermission = null
            if (request != null) {
                val allowed = mutableListOf<String>()
                if (result[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA))
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources)
                        allowed.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (result[Manifest.permission.RECORD_AUDIO] == true || hasPermission(Manifest.permission.RECORD_AUDIO))
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources)
                        allowed.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
            }
            if (result[Manifest.permission.RECORD_AUDIO] == false) onMicDenied()
        }

    // Microphone for the native speech bridge (see withMicPermission).
    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val waiting = micCallbacks.toList()
            micCallbacks.clear()
            waiting.forEach { it(granted) }
            if (!granted) onMicDenied()
        }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Runs [onResult] once the app's microphone permission is known: immediately if already
     * granted, otherwise after Android's one-time permission dialog.
     */
    fun withMicPermission(onResult: (Boolean) -> Unit) {
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            onResult(true)
            return
        }
        val firstWaiter = micCallbacks.isEmpty()
        micCallbacks.add(onResult)
        if (firstWaiter) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // The microphone was refused. If Android won't ask again, the only fix is the app's settings
    // page, so offer to open it (instead of leaving the user stuck on a dead mic button).
    private fun onMicDenied() {
        if (isFinishing || isDestroyed) return
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) return
        AlertDialog.Builder(this)
            .setTitle("Microphone is off for Acazen")
            .setMessage("Turn on Microphone in Acazen's app settings to talk to Acazen.")
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", packageName, null))
                    )
                } catch (e: Exception) {
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    // When the connection comes back, close the offline screen and reload the page.
    // When it drops, show the offline screen immediately instead of waiting for whatever
    // the WebView happens to be doing to time out and fail on its own.
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                runOnUiThread { if (offlineShown && offlineMode) leaveOffline() }
            }
        }

        override fun onLost(network: Network) {
            runOnUiThread { if (!isConnected()) showOffline(siteIssue = false) }
        }

        override fun onUnavailable() {
            runOnUiThread { showOffline(siteIssue = false) }
        }
    }

    // Exposed only to the local offline page (never to the website).
    inner class OfflineBridge {
        @JavascriptInterface
        fun retry(): Boolean {
            if (!isConnected()) return false
            runOnUiThread { leaveOffline() }
            return true
        }
    }

    // Voice-shortcut control, exposed to the website so its User Menu settings page can toggle
    // the hold-Power voice feature on/off. Only ever attached to acazen.com pages (never the
    // offline page) — see the addJavascriptInterface call below.
    inner class AssistantBridge {
        // The old "Hey Acazen" wake word was REMOVED in 2.12.0 (always-on Vosk mic listening
        // proved unreliable and drained battery). These four methods are kept as inert stubs so
        // any cached web UI that still calls them simply sees "unsupported" instead of throwing
        // "AcazenAssistant.isSupported is not a function" inside the WebView. New web builds
        // must use the isDoublePress* / *DoublePress methods below — that is the Siri-style path.
        @JavascriptInterface
        fun isSupported(): Boolean = false

        @JavascriptInterface
        fun isEnabled(): Boolean = false

        @JavascriptInterface
        fun enable() {}

        @JavascriptInterface
        fun disable() {}

        // ---- hold-Power voice shortcut (Siri-style) --------------------------------------
        // THE wake feature now: Settings > "Hold Power for Acazen". Acazen registers as the
        // device's Digital Assistant app (RoleManager.ROLE_ASSISTANT) so the SYSTEM itself
        // routes its native power-button-hold-for-assistant gesture straight to
        // AcazenInteractionSession — no key interception, no accessibility service, and it
        // costs zero battery (nothing runs until the gesture fires, lock screen included).

        @JavascriptInterface
        fun isDoublePressSupported(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        // TRUE source of truth: RoleManager.isRoleHeld(), read fresh every call (never cached —
        // that's what caused the old "setting never saves" bug with the accessibility toggle).
        // Also refreshes the local mirror pref the web UI can read without a bridge round-trip.
        @JavascriptInterface
        fun isDoublePressServiceOn(): Boolean {
            val on = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    getSystemService(android.app.role.RoleManager::class.java)
                        ?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) ?: false
                } catch (e: Exception) { false }
            } else false
            DoublePressPrefs.setEnabled(this@MainActivity, on)
            return on
        }

        // Opens the dedicated native setup page (animated step checklist: mic -> make Acazen
        // the Assistant). The website sheet routes here instead of doing its own half-flow.
        @JavascriptInterface
        fun openDoublePressSetup() {
            runOnUiThread { AssistantSetupActivity.launch(this@MainActivity) }
        }

        @JavascriptInterface
        fun enableDoublePress() {
            runOnUiThread {
                // Don't fake-enable a pref that can't be honored (the Assistant role must be
                // granted by the user via the system role dialog). Route to the guided setup page.
                AssistantSetupActivity.launch(this@MainActivity)
            }
        }

        @JavascriptInterface
        fun disableDoublePress() {
            runOnUiThread {
                DoublePressPrefs.setEnabled(this@MainActivity, false)
                // Android has no API for an app to give up a role on the user's behalf; send
                // them to the picker where they can choose a different default assistant.
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light-only app: the status/navigation bars always get dark icons over the website's
        // light colors, even when the phone itself is in dark mode.
        val lightBars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = lightBars, navigationBarStyle = lightBars)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        splash = findViewById(R.id.splash)
        offline = findViewById(R.id.offline)
        padForSystemBars(findViewById(R.id.root))
        padForSystemBars(offline)
        val refresh = findViewById<SwipeRefreshLayout>(R.id.refresh)
        refresh.isEnabled = false   // no pull-to-refresh: the site controls its own reloads
        val progress = findViewById<ProgressBar>(R.id.progress)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            // Lets the website know it is running inside the app.
            userAgentString = "$userAgentString $APP_UA"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Safe to attach unconditionally: shouldOverrideUrlLoading below sends every link that
        // isn't acazen.com (or a scheme like tel:/mailto:) out to another app, so this WebView
        // only ever actually renders acazen.com's own pages.
        webView.addJavascriptInterface(AssistantBridge(), "AcazenAssistant")

        // Voice mode on the website: the WebView has no Web Speech API of its own, so the site's
        // speech recognition is handed to the phone's recognizer (see NativeSpeechBridge). It
        // refuses to run for anything but acazen.com pages.
        speechBridge = NativeSpeechBridge(this, webView)
        webView.addJavascriptInterface(speechBridge, "AcazenSTT")

        webView.webViewClient = object : WebViewClient() {
            // Web pages stay inside the app; tel:, mailto:, etc. go to the matching app.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme in setOf("http", "https", "about", "javascript", "blob", "data")) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectSpeechShim(view, url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                injectSpeechShim(view, url)
                // A real page loaded successfully: the failed-load history entry (if any)
                // is behind us, so the back button is safe to use again.
                if (url != null && url != "about:blank") failedUrl = null
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    failedUrl = request.url.toString()
                    // Chromium paints its own "can't reach this page" interstitial (with the
                    // URL on it) into the WebView right as this fires. Blank the WebView first,
                    // synchronously, so nothing with the site's address is ever visible beneath
                    // the offline overlay, regardless of the overlay's fade-in timing.
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    showOffline(siteIssue = isValidated())
                    pageReady = true
                    hideSplashIfReady()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams
            ): Boolean {
                startChooser(callback, params)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsCamera = PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources
                val wantsMic = PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources
                if (!isAcazenUrl(request.origin.toString()) || (!wantsCamera && !wantsMic)) {
                    request.deny()
                    return
                }
                val missing = mutableListOf<String>()
                if (wantsCamera && !hasPermission(Manifest.permission.CAMERA)) missing.add(Manifest.permission.CAMERA)
                if (wantsMic && !hasPermission(Manifest.permission.RECORD_AUDIO)) missing.add(Manifest.permission.RECORD_AUDIO)
                if (missing.isEmpty()) {
                    val granted = mutableListOf<String>()
                    if (wantsCamera) granted.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (wantsMic) granted.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    request.grant(granted.toTypedArray())
                } else {
                    pendingWebPermission?.deny()
                    pendingWebPermission = request
                    webPermissionLauncher.launch(missing.toTypedArray())
                }
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress in 10..99) injectSpeechShim(view, view.url)
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                if (newProgress == 100) {
                    pageReady = true
                    hideSplashIfReady()
                }
            }
        }

        webView.setDownloadListener { url, _, _, _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Guard on the overlay actually being off-screen (not just the flag, which
                // flips before its fade-out finishes) and on there being no pending failed
                // load, so back can never land the WebView on the broken page's history entry.
                val offlineVisible = offlineShown || offline.visibility == View.VISIBLE
                if (!offlineVisible && failedUrl == null && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        if (savedInstanceState == null) {
            startSplash()
            if (isConnected()) {
                webView.loadUrl(homeUrlFor(intent))
            } else {
                failedUrl = HOME
                pageReady = true
                showOffline(siteIssue = false)
            }
        } else {
            splashGone = true
            splash.visibility = View.GONE
            webView.restoreState(savedInstanceState)
            handleWakeWordIntent(intent)
        }
    }

    // Reused whenever MainActivity is already running (singleTop/reorder-to-front) and
    // "Hey Acazen" wakes it again, or the user taps the listening notification.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeWordIntent(intent)
    }

    // If woken by the wake word (or the hold-Power voice overlay) while already on-screen and
    // connected, jump straight into voice mode instead of reloading the whole page from scratch.
    private fun handleWakeWordIntent(intent: Intent) {
        if (intent.action != ACTION_WAKE_WORD && intent.action != VoiceCommandActivity.ACTION_VOICE_COMMAND) return
        if (offlineShown) return // don't interrupt the offline screen with a reload
        if (isConnected()) {
            webView.loadUrl(homeUrlFor(intent))
        }
    }

    // HOME with "?voice=1" appended when launched from the wake word, so the page knows to
    // start listening immediately (see index.html's ?voice=1 handler). When the hand-off came
    // from the hold-Power overlay carrying recognized speech, the text rides along as
    // &voice_text=... (URL-encoded) — the site auto-sends it; see MainActivity's companion
    // note. The website strips both params from history right after consuming them.
    private fun homeUrlFor(intent: Intent): String {
        val wake = intent.action == ACTION_WAKE_WORD ||
            intent.action == VoiceCommandActivity.ACTION_VOICE_COMMAND
        if (!wake) return HOME
        val spoken = intent.getStringExtra(VoiceCommandActivity.EXTRA_VOICE_TEXT)
        return if (spoken.isNullOrEmpty()) {
            "$HOME?voice=1"
        } else {
            "$HOME?voice=1&$VOICE_TEXT_PARAM=" + Uri.encode(spoken)
        }
    }

    // Defines the website's speech recognition on top of the phone's recognizer (acazen.com only).
    private fun injectSpeechShim(view: WebView, url: String?) {
        if (!isAcazenUrl(url)) return
        view.evaluateJavascript(NativeSpeechBridge.POLYFILL_JS, null)
    }

    // ---- connectivity -------------------------------------------------------------------------

    private fun caps() = connectivity.getNetworkCapabilities(connectivity.activeNetwork)

    private fun isConnected() =
        caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun isValidated() =
        caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    override fun onStart() {
        super.onStart()
        connectivity.registerDefaultNetworkCallback(netCallback)
    }

    override fun onStop() {
        speechBridge.release() // never keep the microphone open while the app is in the background
        try {
            connectivity.unregisterNetworkCallback(netCallback)
        } catch (e: Exception) {
        }
        super.onStop()
    }

    // ---- offline screen (with game) -----------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun showOffline(siteIssue: Boolean) {
        if (offlineShown) return
        offlineShown = true
        offlineMode = !siteIssue
        offline.animate().cancel()
        offline.removeAllViews()
        gameView?.destroy()

        val gv = WebView(this)
        gv.setBackgroundColor(ContextCompat.getColor(this, R.color.splash_bg))
        gv.settings.javaScriptEnabled = true
        gv.settings.domStorageEnabled = true
        gv.settings.blockNetworkLoads = true
        gv.addJavascriptInterface(OfflineBridge(), "AcazenNative")
        offline.addView(gv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        gameView = gv
        gv.loadUrl(OFFLINE_PAGE + if (siteIssue) "?site" else "")

        // Shown instantly, not faded in: any delay here is extra time the broken page
        // (or a blank frame) stays visible underneath.
        offline.alpha = 1f
        offline.visibility = View.VISIBLE
        ViewCompat.requestApplyInsets(offline)
    }

    private fun hideOffline() {
        if (!offlineShown) return
        offlineShown = false
        offline.animate().alpha(0f).setDuration(250).withEndAction {
            offline.visibility = View.GONE
            offline.removeAllViews()
            gameView?.destroy()
            gameView = null
        }.start()
    }

    private fun leaveOffline() {
        if (!offlineShown || leavingOffline) return
        leavingOffline = true
        // Let the game play its little victory moment first, then swap back to the
        // real site. If the game can't be reached for some reason, fall back to the
        // plain transition after a short timeout rather than getting stuck.
        var done = false
        val finish = Runnable {
            if (done) return@Runnable
            done = true
            leavingOffline = false
            hideOffline()
            val url = failedUrl
            failedUrl = null
            if (url != null) webView.loadUrl(url) else webView.reload()
        }
        try {
            gameView?.evaluateJavascript("window.acazenLeave && window.acazenLeave()") { }
        } catch (e: Exception) {
        }
        offline.postDelayed(finish, LEAVE_ANIM_MS)
    }

    private fun padForSystemBars(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    // ---- opening animation --------------------------------------------------------------------

    private fun startSplash() {
        // Ambient Lottie particle/ring animation behind the logo. Fades in slightly
        // after the splash itself appears so it doesn't compete with the logo pop-in.
        val lottieView = findViewById<LottieAnimationView>(R.id.splash_lottie)
        lottieView.playAnimation()
        lottieView.animate().alpha(1f).setStartDelay(150).setDuration(600).start()

        val logo = findViewById<ImageView>(R.id.splash_logo)
        // Uses res/drawable-nodpi/acazen.png when present, otherwise a placeholder.
        val logoRes = resources.getIdentifier("acazen", "drawable", packageName)
        logo.setImageResource(if (logoRes != 0) logoRes else R.drawable.splash_logo_placeholder)

        // 1. The framed logo pops in with a soft bounce.
        val frame = findViewById<View>(R.id.splash_logo_frame)
        frame.alpha = 0f
        frame.scaleX = 0.4f
        frame.scaleY = 0.4f
        frame.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(800)
            .setInterpolator(OvershootInterpolator(1.6f)).start()

        // 2. A soft loading bar fades in below and fills while the site loads.
        val loader = findViewById<ProgressBar>(R.id.splash_loader)
        loader.progress = 0
        loader.animate().alpha(1f).setStartDelay(500).setDuration(500).start()
        loaderAnim = ObjectAnimator.ofInt(loader, "progress", 0, 900).apply {
            duration = SPLASH_MIN_MS + 400
            startDelay = 400
            interpolator = DecelerateInterpolator(1.3f)
            start()
        }.also { pulses += it }

        // 3. Two soft ripples pulse out from behind the logo.
        for ((ringId, delay) in listOf(R.id.ring1 to 500L, R.id.ring2 to 1400L)) {
            pulses += ObjectAnimator.ofPropertyValuesHolder(
                findViewById<View>(ringId),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 2.3f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 2.3f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0f)
            ).apply {
                duration = 1900
                startDelay = delay
                repeatCount = ObjectAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                start()
            }
        }

        splash.postDelayed({ splashMinDone = true; hideSplashIfReady() }, SPLASH_MIN_MS)
        splash.postDelayed({ hideSplash() }, SPLASH_MAX_MS)
    }

    private fun hideSplashIfReady() {
        if (splashMinDone && pageReady) hideSplash()
    }

    // The bar fills to 100%, then the logo grows slightly while the splash fades away.
    private fun hideSplash() {
        if (splashGone) return
        splashGone = true
        loaderAnim?.cancel()
        val loader = findViewById<ProgressBar>(R.id.splash_loader)
        ObjectAnimator.ofInt(loader, "progress", loader.progress, 1000).apply {
            duration = 250
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = fadeOutSplash()
            })
            start()
        }
    }

    private fun fadeOutSplash() {
        findViewById<View>(R.id.splash_logo_frame).animate()
            .scaleX(1.25f).scaleY(1.25f).setDuration(450).start()
        splash.animate().alpha(0f).setDuration(450).withEndAction {
            splash.visibility = View.GONE
            pulses.forEach { it.cancel() }
            findViewById<LottieAnimationView>(R.id.splash_lottie).cancelAnimation()
        }.start()
    }

    // ---- file uploads (photo / PDF pickers) ---------------------------------------------------

    private fun acceptTypes(params: WebChromeClient.FileChooserParams) =
        params.acceptTypes
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

    private fun startChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams) {
        finishChooser(null)   // cancel any chooser that is still open
        fileCallback = callback
        chooserParams = params
        val photos = acceptTypes(params).any {
            it.startsWith("image/") || it in setOf(".jpg", ".jpeg", ".png", ".webp")
        }
        when {
            photos && params.isCaptureEnabled -> startCapture()
            photos -> showUploadChoices()
            else -> openPicker()
        }
    }

    // Must always be called, otherwise the website's file button stops responding.
    private fun finishChooser(uris: Array<Uri>?) {
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private fun showUploadChoices() {
        AlertDialog.Builder(this)
            .setTitle("Upload")
            .setItems(arrayOf("Take a photo", "Choose from gallery or files")) { _, which ->
                if (which == 0) startCapture() else openPicker()
            }
            .setOnCancelListener { finishChooser(null) }
            .show()
    }

    private fun openPicker() {
        val params = chooserParams
        if (params == null) {
            finishChooser(null)
            return
        }
        // Turn ".pdf"-style entries into MIME types so the system picker filters correctly.
        val types = acceptTypes(params)
            .mapNotNull {
                if (it.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.drop(1)) else it
            }
            .distinct()
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
            )
            if (types.size > 1) {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            } else {
                type = types.firstOrNull() ?: "*/*"
            }
        }
        try {
            filePicker.launch(intent)
        } catch (e: Exception) {
            finishChooser(null)
        }
    }

    private fun startCapture() {
        val cam = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, cam) == PackageManager.PERMISSION_GRANTED) {
            launchCapture()
        } else {
            capturePermission.launch(cam)
        }
    }

    private fun launchCapture() {
        try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            val photo = File.createTempFile("photo_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photo)
            capturedUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            openPicker()   // no camera app available: fall back to the picker
        }
    }

    override fun onDestroy() {
        if (::speechBridge.isInitialized) speechBridge.release()
        finishChooser(null)
        pulses.forEach { it.cancel() }
        offline.removeCallbacks(null)
        gameView?.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }
}
