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
        // Set on the Intent WakeWordService launches us with when "Hey Acazen" fires.
        const val ACTION_WAKE_WORD = "com.acazen.app.action.WAKE_WORD"
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
    private var pendingCamera: PermissionRequest? = null

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

    // Camera access for the website (e.g. Smart focus). Android asks the user once.
    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = pendingCamera
            pendingCamera = null
            if (granted) request?.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else request?.deny()
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

    // "Hey Acazen" wake word control, exposed to the website so its User Menu toggle can turn
    // the feature on/off. Only ever attached to acazen.com pages (never the offline page) —
    // see the addJavascriptInterface call below, which is gated the same way onPermissionRequest
    // already gates camera access.
    inner class AssistantBridge {
        // The web UI calls this before showing its own toggle, so it can show "only supported
        // in the app" instead of a dead switch when running in a plain mobile browser. Since
        // this whole class is only attached inside the app, a true return just confirms we're
        // in a build where the wake-word keyword file + access key were actually included.
        @JavascriptInterface
        fun isSupported(): Boolean =
            BuildConfig.PORCUPINE_ACCESS_KEY.isNotBlank() && assetExists(WakeWordService.KEYWORD_ASSET_PATH)

        @JavascriptInterface
        fun isEnabled(): Boolean = WakeWordPrefs.isEnabled(this@MainActivity)

        // Starts the permission flow (mic, then notifications on Android 13+) and only turns
        // the feature on once both are granted. Safe to call repeatedly.
        @JavascriptInterface
        fun enable() {
            runOnUiThread { beginEnableWakeWord() }
        }

        @JavascriptInterface
        fun disable() {
            runOnUiThread { disableWakeWord() }
        }
    }

    private fun assetExists(path: String): Boolean =
        try {
            assets.open(path).use { true }
        } catch (e: Exception) {
            false
        }

    private fun beginEnableWakeWord() {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            wakeWordMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        requestNotificationPermissionThenStart()
    }

    private fun requestNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                wakeWordNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startWakeWordService()
    }

    private fun startWakeWordService() {
        WakeWordPrefs.setEnabled(this, true)
        val serviceIntent = Intent(this, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun disableWakeWord() {
        WakeWordPrefs.setEnabled(this, false)
        val serviceIntent = Intent(this, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_STOP
        }
        try {
            startService(serviceIntent)
        } catch (e: Exception) {
            // Service may already be stopped — nothing to do.
        }
    }

    // Mic permission for the wake word (separate from the website's own camera permission flow
    // above). If the user denies it, Hey Acazen simply stays off — enable() can be called again.
    private val wakeWordMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestNotificationPermissionThenStart()
        }

    // Android 13+ requires this to show the persistent "listening" notification. Without it the
    // foreground service would still run (mic access doesn't require it) but the user would have
    // no way to see or stop it from the notification shade, so we still require it up front.
    private val wakeWordNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Start regardless: notifications are nice-to-have for visibility/control, but the
            // persistent low-importance channel also surfaces via Settings > Apps either way,
            // and withholding the whole feature over this one optional permission would be
            // surprising to a user who just said yes to the mic.
            startWakeWordService()
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

            override fun onPageFinished(view: WebView, url: String?) {
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
                val host = request.origin.host.orEmpty()
                val fromSite = request.origin.scheme == "https" &&
                    (host == "acazen.com" || host.endsWith(".acazen.com"))
                if (!fromSite || PermissionRequest.RESOURCE_VIDEO_CAPTURE !in request.resources) {
                    request.deny()
                    return
                }
                val cam = Manifest.permission.CAMERA
                if (ContextCompat.checkSelfPermission(this@MainActivity, cam) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    pendingCamera?.deny()
                    pendingCamera = request
                    cameraLauncher.launch(cam)
                }
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
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

    // If woken by the wake word while already on-screen and connected, jump straight into
    // voice mode instead of reloading the whole page from scratch.
    private fun handleWakeWordIntent(intent: Intent) {
        if (intent.action != ACTION_WAKE_WORD) return
        if (offlineShown) return // don't interrupt the offline screen with a reload
        if (isConnected()) {
            webView.loadUrl(homeUrlFor(intent))
        }
    }

    // HOME with "?voice=1" appended when launched from the wake word, so the page knows to
    // start listening immediately (see index.html's ?voice=1 handler).
    private fun homeUrlFor(intent: Intent): String =
        if (intent.action == ACTION_WAKE_WORD) "$HOME?voice=1" else HOME

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
