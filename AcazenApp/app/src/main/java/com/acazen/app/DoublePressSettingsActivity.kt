package com.acazen.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.text.SpannableString
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * THE dedicated, beautiful setup page for the hold-Power wake shortcut.
 *
 * It replaces the old "toggle inside the website sheet" flow, which had two real bugs:
 *
 *  1. The web toggle only wrote an app-side preference and jumped to the system Accessibility
 *     list — nothing ever confirmed completion, so it LOOKED like the setting never saved.
 *  2. VoiceCommandActivity refused to listen when RECORD_AUDIO wasn't granted yet (the gesture
 *     then fell through to whatever the OEM maps double-Power to — wallpaper/camera).
 *
 * This page drives the whole thing natively with an animated step checklist:
 *     Step 1 — Microphone permission   (one-tap runtime dialog)
 *     Step 2 — Accessibility service   (deep-links straight to OUR service's detail screen;
 *                                       state is read from Settings.Secure, not just from the
 *                                       running instance, so it survives service restarts)
 *     Step 3 — Try it                  (live test overlay right here — proves the gesture works
 *                                       before the user ever locks the phone)
 * Every step re-checks itself on resume and animates a green check in when satisfied, so the
 * state is always honest and visibly "saved".
 */
class DoublePressSettingsActivity : ComponentActivity() {

    companion object {
        /** Entry point used by the website bridge (MainActivity) and the notification. */
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, DoublePressSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }

        private const val ACC_SETTINGS_ACTION = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Step rows + their status pills, rebuilt once and updated in place.
    private lateinit var micPill: TextView
    private lateinit var accPill: TextView
    private lateinit var testPill: TextView
    private lateinit var primaryButton: Button
    private lateinit var stepList: LinearLayout
    private var lastTestAt = 0L
    private var testedOkOnce = false

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh(animated = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refresh(animated = false)

        // Staggered entrance: hero + each step card fades/slides in one after another.
        var delay = 0L
        for (i in 0 until stepList.childCount) {
            val child = stepList.getChildAt(i)
            child.alpha = 0f
            child.translationY = dp(14).toFloat()
            child.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(320).start()
            delay += 110
        }
    }

    override fun onResume() {
        super.onResume()
        // User may have flipped the accessibility switch while we were paused in Settings —
        // re-read everything the moment they come back. THIS is what makes the toggle finally
        // appear to "save": truth is polled from the system, not remembered from a click.
        refresh(animated = true)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---- state -------------------------------------------------------------------------------

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Authoritative enabled-state of our accessibility service, read from secure settings.
     * `DoublePressPowerService.isRunning()` alone was flaky (the instance pointer can lag or be
     * cleared while Android rebinds the service), which made an ON feature show as OFF.
     */
    private fun accessibilityOn(): Boolean =
        DoublePressPrefs.isServiceEnabledInSettings(this) || DoublePressPowerService.isRunning()

    // ---- actions -----------------------------------------------------------------------------

    private fun requestMic() = micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

    /**
     * Deep-link directly to OUR service's own toggle screen inside system Settings
     * (Android 7+). Falling back to the plain accessibility list keeps older/OEM builds working.
     */
    private fun openAccessibilityDetail() {
        try {
            val id = "$packageName/$packageName.DoublePressPowerService"
            val intent = Intent(ACC_SETTINGS_ACTION).apply {
                putExtra(":settings:fragment_args_key", id) // legacy SystemUI arg
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Some OEMs key off these instead:
            intent.putExtra("key", id)
            intent.putExtra(":settings:show_fragment_args_key", id)
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                return
            }
        } catch (e: Exception) { /* fall through */ }
        try {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            toast("Open Settings > Accessibility > Installed apps > Acazen manually")
        }
    }

    /**
     * Live gesture rehearsal INSIDE this page: shows the exact same listening overlay the
     * double-press will summon (VoiceCommandActivity in TEST mode). If the user can complete it
     * here, the real gesture is guaranteed to work too — no more guessing why "nothing happens".
     */
    private fun runLiveTest() {
        lastTestAt = System.currentTimeMillis()
        testPill.text = "Testing…"
        testPill.setBackgroundColor(Color.parseColor("#FFF7E6"))
        testPill.setTextColor(Color.parseColor("#B76E00"))
        try {
            VoiceCommandActivity.launchTest(this)
        } catch (e: Exception) {
            toast("Couldn't open the test overlay")
        }
    }

    private fun openAppSettingsPage() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
        }
    }

    // ---- UI ------------------------------------------------------------------------------------

    private fun buildUi(): View {
        val bg = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F3F7FC"))
        }

        // Status bar space (edge-to-edge look without the library): a colored spacer.
        val spacerTop = View(this)
        bg.addView(spacerTop, lp(w = -1, h = statusBarPx()))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(24))
        }
        scroll.addView(col)
        bg.addView(scroll, lp(w = -1, h = -2))

        // Hero ------------------------------------------------------------------
        val heroIcon = TextView(this).apply {
            text = "⏻"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A73E8"))
            gravity = Gravity.CENTER
            background = rounded("#E8F0FE", 22)
        }
        val heroWrap = FrameLayout(this)
        heroWrap.addView(
            heroIcon,
            FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER)
        )
        col.addView(heroWrap, lp(w = -1, h = dp(96)))

        col.addView(titleText("Hold Power button", 24f, "#112133", bold = true, top = 0))
        col.addView(bodyText(
            "Wake Acazen anywhere, even on the lock screen — exactly like holding the Side button for Assistant. " +
                "HOLD Power for ~" + (DoublePressPowerService.HOLD_TRIGGER_MS / 1000.0) + "s → Acazen pops up listening → say your question → it is sent automatically.",
            top = 6
        ))

        // Battery note card ------------------------------------------------------
        val note = TextView(this).apply {
            text = "🔋  No always-on microphone. Nothing runs in the background until you press the button — zero battery drain."
            textSize = 12.5f
            setTextColor(Color.parseColor("#3C5A78"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded("#EAF6EF", 14)
        }
        col.addView(note, lp(w = -1, h = -2, top = 16))

        // Steps ------------------------------------------------------------------
        col.addView(titleText("Set up in 3 steps", 15f, "#112133", bold = true, top = 26))

        micPill = pill("Not done", "#FDECEC", "#C5221F")
        accPill = pill("Off", "#EEF2F7", "#5A7A9A")
        testPill = pill("Ready", "#EEF2F7", "#5A7A9A")

        stepList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stepList.addView(
            stepRow(
                "1", "Microphone access",
                "Lets the overlay hear what you say.",
                micPill
            ) { if (!micGranted()) requestMic() else toast("Microphone already allowed ✅") }
        )
        stepList.addView(
            stepRow(
                "2", "Turn on the shortcut",
                "Flip \"Acazen — Hold Power Voice\" ON in the screen that opens, then come back here.",
                accPill
            ) { openAccessibilityDetail() }
        )
        stepList.addView(
            stepRow(
                "3", "Try it right now",
                "Opens the real listening overlay so you can feel it work before locking the phone.",
                testPill
            ) { runLiveTest() }
        )
        col.addView(stepList, lp(w = -1, h = -2, top = 8))

        // Primary CTA --------------------------------------------------------------
        primaryButton = Button(this).apply {
            text = "Continue setup"
            textSize = 16f
            setTextColor(Color.WHITE)
            isAllCaps = false
            stateListAnimator = null
            background = roundedBtn("#1A73E8", 16)
            setOnClickListener { onPrimary() }
        }
        col.addView(primaryButton, lp(w = -1, h = dp(54), top = 20))

        // How it works ---------------------------------------------------------------
        col.addView(titleText("How it works", 15f, "#112133", bold = true, top = 30))
        col.addView(bodyText(
            "① HOLD the Power button for about " + (DoublePressPowerService.HOLD_TRIGGER_MS / 1000.0) + " seconds (the screen stays as it is).\n" +
                "② Acazen appears full-screen with a pulsing mic and writes what it hears.\n" +
                "③ When you stop talking, the question is sent to Acazen by itself.",
            top = 8
        ))

        // Troubleshooting -------------------------------------------------------------
        col.addView(titleText("If holding Power still opens something else", 15f, "#112133", bold = true, top = 26))
        col.addView(bodyText(
            "Some phones map long/held Power presses to their own features (power menu, Assistant, camera). " +
                "Our service cancels the power menu automatically while enabled; if yours still shows one, turn these OFF:\n\n" +
                "• Pixel / stock: Settings › System › Gestures › Power button › hold for Assistant = Off\n" +
                "• Samsung: Settings › Advanced features › Side key\n" +
                "• Xiaomi/Redmi: Settings › Always-on display & Lock screen / Extra settings\n" +
                "• Then also disable any live wallpaper's \"tap/power gestures\": Settings › Wallpaper & style",
            top = 8
        ))

        val oem = SpannableString("Open Acazen's app settings (battery: set to Unrestricted) →")
        oem.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) = openAppSettingsPage()
        }, 0, oem.length, 0)
        val oemLink = TextView(this).apply {
            text = oem
            movementMethod = LinkMovementMethod.getInstance()
            setTextColor(Color.parseColor("#1A73E8"))
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        }
        col.addView(oemLink)

        return bg
    }

    private fun onPrimary() {
        when {
            !micGranted() -> requestMic()
            !accessibilityOn() -> openAccessibilityDetail()
            else -> runLiveTest()
        }
    }

    private fun stepRow(
        num: String,
        title: String,
        desc: String,
        pill: TextView,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded("#FFFFFF", 18)
            elevation = dp(2).toFloat()
            setOnClickListener { onClick() }
        }
        val badge = TextView(this).apply {
            text = num
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A73E8"))
            gravity = Gravity.CENTER
            background = rounded("#E8F0FE", 20)
        }
        row.addView(badge, lp(w = dp(40), h = dp(40), margin = true))

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        texts.addView(titleText(title, 15f, "#112133", bold = true, top = 0))
        texts.addView(bodyText(desc, top = 3, size = 12.5f))
        val tl = lp(w = 0, h = -2)
        tl.weight = 1.0f
        texts.layoutParams = tl
        row.addView(texts)

        val pl = lp(w = -2, h = -2, margin = true)
        pl.gravity = Gravity.CENTER_VERTICAL
        row.addView(pill, pl)
        return row
    }

    private fun pill(text: String, bgHex: String, fgHex: String) = TextView(this).apply {
        this.text = text
        textSize = 11.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor(fgHex))
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = rounded(bgHex, 12)
        gravity = Gravity.CENTER
    }

    private fun titleText(t: String, size: Float, color: String, bold: Boolean, top: Int): TextView {
        val v = TextView(this).apply {
            text = t
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(color))
            layoutParams = lp(w = -1, h = -2, top = top)
        }
        return v
    }

    private fun bodyText(t: String, top: Int, size: Float = 14f) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(Color.parseColor("#445A70"))
        layoutParams = lp(w = -1, h = -2, top = top)
    }

    private fun rounded(hex: String, radiusDp: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor(hex))
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun roundedBtn(hex: String, radiusDp: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor(hex))
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun lp(w: Int, h: Int, top: Int = 0, margin: Boolean = false): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply {
            if (top != 0) topMargin = dp(top)
            if (margin) setMargins(dp(10), 0, dp(10), 0)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun statusBarPx(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
        } else dp(24)

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---- live state sync ------------------------------------------------------------------------

    private fun refresh(animated: Boolean) {
        val mic = micGranted()
        val acc = accessibilityOn()
        val recentlyTested = System.currentTimeMillis() - lastTestAt < 60_000L

        setPill(micPill, if (mic) "Done ✓" else "Needed",
            if (mic) "#E6F4EA" else "#FDECEC", if (mic) "#137333" else "#C5221F", animated && mic)
        setPill(accPill, if (acc) "On ✓" else "Off",
            if (acc) "#E6F4EA" else "#EEF2F7", if (acc) "#137333" else "#5A7A9A", animated && acc)
        val realFire = DoublePressPrefs.lastTriggerAt(this) > lastTestAt &&
            System.currentTimeMillis() - DoublePressPrefs.lastTriggerAt(this) < 120_000L
        setPill(testPill,
            when {
                realFire -> "Works ✓"
                recentlyTested || testedOkOnce -> "Tried ✓"
                else -> "Ready"
            },
            "#E6F4EA", "#137333", animate = false)

        // Persist the combined outcome so the website sheet can show an honest On/Off.
        DoublePressPrefs.setEnabled(this, acc)

        primaryButton.text = when {
            !mic -> "Allow microphone"
            !acc -> "Turn on in Accessibility"
            else -> "Try it: open the listening overlay"
        }

        if (mic && acc) {
            // Feature fully live — pop the confirmation once so it visibly "saved".
            if (animated && !announcedReady) {
                announcedReady = true
                val keysSeen = DoublePressPowerService.sawAnyPowerKey
                val heal = DoublePressPowerService.didSelfHeal
                val holdSecs = DoublePressPowerService.HOLD_TRIGGER_MS / 1000.0
                toast((if (keysSeen)
                    "✅ Hold-Power is ON — press and HOLD Power for ~${holdSecs}s anywhere to speak"
                else
                    "Almost done ✅ — now try it: press and HOLD Power for ~${holdSecs}s (release when Acazen appears)")
                    + (if (heal) "\n⚠️ MIUI blocked the gesture once — auto-repaired. If the power menu still appears, keep holding until Acazen pops (it cancels the menu)." else ""))
            }
        } else {
            announcedReady = false
        }
    }

    private var announcedReady = false

    private fun setPill(pill: TextView, text: String, bgHex: String, fgHex: String, animate: Boolean) {
        val changed = pill.text != text
        pill.text = text
        pill.setBackgroundColor(Color.parseColor(bgHex))
        pill.setTextColor(Color.parseColor(fgHex))
        if (animate && changed) {
            pill.scaleX = 0.6f
            pill.scaleY = 0.6f
            pill.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(
                android.view.animation.OvershootInterpolator()
            ).start()
        }
    }

}
