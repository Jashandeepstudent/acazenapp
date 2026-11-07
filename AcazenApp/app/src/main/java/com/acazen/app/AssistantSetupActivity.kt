package com.acazen.app

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
 * THE setup page for Acazen's hold-Power voice shortcut — replaces the old
 * DoublePressSettingsActivity, which drove an AccessibilityService that could never actually
 * see the Power key (see AcazenInteractionService's doc comment for the full explanation).
 *
 * This page does the one thing that actually makes "hold Power → Acazen" work: ask the user to
 * make Acazen their device's registered Digital Assistant app
 * (RoleManager.ROLE_ASSISTANT). Once granted, the OS itself — not Acazen — owns the power-button
 * gesture and routes it straight to AcazenInteractionSession.
 *
 * DEBUG BUILD: has an on-screen log panel (see `log()` and the "Debug log" section in buildUi())
 * so every branch of the step-2 click path is visible directly on the device without a cable or
 * Android Studio — remove the panel + log() calls once step 2 is confirmed working end to end.
 *
 * Same two-honest-truths design as before:
 *   1. RoleManager.createRequestRoleIntent(ROLE_ASSISTANT) does not always show a confirmation
 *      dialog on every OEM build; the reliable fallback is deep-linking to
 *      Settings.ACTION_VOICE_INPUT_SETTINGS ("Default apps › Assist & voice input") and
 *      re-checking isRoleHeld() when the user comes back — exactly like the old page re-checked
 *      Settings.Secure for the accessibility toggle.
 *   2. State is always polled fresh from RoleManager on resume, never assumed from a click, so
 *      the page can never silently lie about whether the shortcut is actually live.
 */
class AssistantSetupActivity : ComponentActivity() {

    companion object {
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, AssistantSetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private lateinit var micPill: TextView
    private lateinit var assistantPill: TextView
    private lateinit var gesturePill: TextView
    private lateinit var testPill: TextView
    private lateinit var primaryButton: Button
    private lateinit var stepList: LinearLayout
    private lateinit var debugScroll: ScrollView
    private lateinit var debugText: TextView
    private var announcedReady = false
    private var testedOkOnce = false

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh(animated = true) }

    private val roleRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            log("roleRequestLauncher returned: resultCode=${result.resultCode}")
            refresh(animated = true)
            log("assistantOn() now = ${assistantOn()}")
            if (!assistantOn()) {
                log("-> still off, falling back to openVoiceInputSettings()")
                openVoiceInputSettings()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refresh(animated = false)

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
        // The user may have granted/changed the assistant role or permission while we were
        // paused in system Settings — re-read everything the moment they come back.
        refresh(animated = true)
    }

    // ---- debug ---------------------------------------------------------------------------------

    private fun log(msg: String) {
        val ts = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        debugText.append("\n[$ts] $msg")
        debugScroll.post { debugScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---- state -------------------------------------------------------------------------------

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun roleManager(): RoleManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            getSystemService(RoleManager::class.java) else null

    private fun roleAvailable(): Boolean =
        roleManager()?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) ?: false

    private fun assistantOn(): Boolean =
        roleManager()?.isRoleHeld(RoleManager.ROLE_ASSISTANT) ?: false

    // ---- actions -----------------------------------------------------------------------------

    private fun requestMic() = micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

    /**
     * Requests ROLE_ASSISTANT. On builds where the role dialog doesn't appear, this call still
     * opens something useful (often the default-apps picker directly); either way onResume()
     * re-checks isRoleHeld() so the step always ends up honest about what actually happened.
     */
    private fun requestAssistantRole() {
        log("tap: requestAssistantRole() called")
        val rm = roleManager()
        log("roleManager() = ${if (rm == null) "NULL (SDK < Q)" else "ok"}")
        if (rm == null) {
            log("-> rm null, falling back to openVoiceInputSettings()")
            openVoiceInputSettings()
            return
        }
        val available = roleAvailable()
        log("roleAvailable() = $available")
        if (!available) {
            log("-> not available, falling back to openVoiceInputSettings()")
            openVoiceInputSettings()
            return
        }
        try {
            log("-> calling createRequestRoleIntent() + launch()")
            roleRequestLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
            log("-> launch() returned, no exception")
        } catch (e: Exception) {
            log("-> EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            openVoiceInputSettings()
        }
    }

    /** Fallback / manual path: "Default apps › Assist & voice input" lets the user pick
     *  Acazen directly even on OEM builds where the role-request dialog misbehaves. */
    private fun openVoiceInputSettings() {
        log("openVoiceInputSettings() called")
        try {
            startActivity(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            log("-> ACTION_VOICE_INPUT_SETTINGS started ok")
        } catch (e: Exception) {
            log("-> ACTION_VOICE_INPUT_SETTINGS failed: ${e.javaClass.simpleName}: ${e.message}")
            try {
                startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                log("-> fell back to ACTION_SETTINGS ok")
                toast("Open Settings › Apps › Default apps › Assist & voice input, then choose Acazen")
            } catch (ignored: Exception) {
                log("-> ACTION_SETTINGS ALSO failed: ${ignored.javaClass.simpleName}: ${ignored.message}")
                toast("Open Settings › Apps › Default apps › Assist & voice input, then choose Acazen")
            }
        }
    }

    /** Live gesture rehearsal: shows the exact same listening overlay the real gesture will
     *  summon (VoiceCommandActivity in TEST mode), so the user can confirm mic + recognizer
     *  work on this device before trusting the Power-hold gesture itself. */
    private fun runLiveTest() {
        testedOkOnce = true
        testPill.text = "Testing…"
        testPill.setBackgroundColor(Color.parseColor("#FFF7E6"))
        testPill.setTextColor(Color.parseColor("#B76E00"))
        try {
            VoiceCommandActivity.launchTest(this)
        } catch (e: Exception) {
            toast("Couldn't open the test overlay")
        }
    }

    /**
     * Most phones don't turn "hold Power for the assistant" on by themselves, and Android doesn't
     * let an app flip it (or even read it) — so this walks the user to the right Settings screen
     * for their brand and asks them to confirm once it's on.
     */
    private fun openGestureGuide() {
        val brand = Build.MANUFACTURER.orEmpty().lowercase()
        val path = when {
            "samsung" in brand ->
                "Settings › Advanced features › Side button › Press and hold → choose “Wake Bixby”. " +
                    "It opens whichever assistant is set as your default, which is Acazen once step 2 is On."
            "xiaomi" in brand || "redmi" in brand || "poco" in brand ->
                "Settings › Additional settings › Button shortcuts → Long press Power → choose the assistant."
            "oneplus" in brand || "oppo" in brand || "realme" in brand ->
                "Settings › Buttons & gestures (or Additional settings › Power button) → Press and hold power button → Assistant."
            "google" in brand ->
                "Settings › System › Gestures › Press & hold power button → choose “Digital assistant”."
            else ->
                "Settings › System › Gestures › Press & hold power button → “Digital assistant”. " +
                    "On other brands look under “Side key”, “Power button” or “Button shortcuts”."
        }
        val message = "Many phones ship with this switched off, so holding Power just opens the power menu.\n\n" +
            "On your phone:\n$path\n\n" +
            "Can't find it? Search Settings for “power button” or “side key”."
        android.app.AlertDialog.Builder(this)
            .setTitle("Turn on hold-Power for Assistant")
            .setMessage(message)
            .setPositiveButton("I turned it on") { _, _ ->
                DoublePressPrefs.setGestureConfirmed(this, true)
                refresh(animated = true)
            }
            .setNeutralButton("Open Settings") { _, _ -> openSystemSettings() }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Open your phone's Settings app and search for “power button”")
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

        val spacerTop = View(this)
        bg.addView(spacerTop, lp(w = -1, h = statusBarPx()))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(24))
        }
        scroll.addView(col)
        bg.addView(scroll, lp(w = -1, h = -2))

        val heroIcon = TextView(this).apply {
            text = "⏻"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A73E8"))
            gravity = Gravity.CENTER
            background = rounded("#E8F0FE", 22)
        }
        val heroWrap = FrameLayout(this)
        heroWrap.addView(heroIcon, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER))
        col.addView(heroWrap, lp(w = -1, h = dp(96)))

        col.addView(titleText("Hold Power for Acazen", 24f, "#112133", bold = true, top = 0))
        col.addView(bodyText(
            "Wake Acazen anywhere, even on the lock screen — hold the Power button the same " +
                "way you would for Google Assistant. Acazen pops up listening, you ask your " +
                "question, and it's sent automatically.",
            top = 6
        ))

        val note = TextView(this).apply {
            text = "🔋  No always-on microphone. Nothing runs until you hold the button — zero battery drain."
            textSize = 12.5f
            setTextColor(Color.parseColor("#3C5A78"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded("#EAF6EF", 14)
        }
        col.addView(note, lp(w = -1, h = -2, top = 16))

        col.addView(titleText("Set up in 4 steps", 15f, "#112133", bold = true, top = 26))

        micPill = pill("Not done", "#FDECEC", "#C5221F")
        assistantPill = pill("Off", "#EEF2F7", "#5A7A9A")
        gesturePill = pill("To do", "#EEF2F7", "#5A7A9A")
        testPill = pill("Ready", "#EEF2F7", "#5A7A9A")

        stepList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stepList.addView(
            stepRow("1", "Microphone access", "Lets the overlay hear what you say.", micPill)
            { if (!micGranted()) requestMic() else toast("Microphone already allowed ✅") }
        )
        stepList.addView(
            stepRow(
                "2", "Make Acazen your Assistant",
                "This is what makes the Power-hold gesture reach Acazen — it's the same system setting Google Assistant and Bixby use.",
                assistantPill
            ) { requestAssistantRole() }
        )
        stepList.addView(
            stepRow(
                "3", "Turn on hold-Power for Assistant",
                "Most phones have this off by default. Switch your Power button's hold action to the assistant — we show you where.",
                gesturePill
            ) { openGestureGuide() }
        )
        stepList.addView(
            stepRow(
                "4", "Try the overlay",
                "Rehearses the exact listening screen so you can confirm mic + recognizer work here, before relying on the real gesture.",
                testPill
            ) { runLiveTest() }
        )
        col.addView(stepList, lp(w = -1, h = -2, top = 8))

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

        // ---- DEBUG LOG PANEL (remove once step 2 is confirmed working) ----
        col.addView(titleText("Debug log", 13f, "#112133", bold = true, top = 18))
        debugText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#4CE868"))
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            text = "— tap a step above —"
        }
        debugScroll = ScrollView(this).apply {
            background = rounded("#0D1117", 10)
            addView(debugText)
        }
        col.addView(debugScroll, lp(w = -1, h = dp(140), top = 8))
        // ---- end debug panel ----

        col.addView(titleText("How it works", 15f, "#112133", bold = true, top = 30))
        col.addView(bodyText(
            "① HOLD the Power button, same as you would for a phone's built-in assistant.\n" +
                "② Acazen appears full-screen with floating light particles around its logo and writes what it hears.\n" +
                "③ When you stop talking, the question is sent to Acazen by itself.",
            top = 8
        ))

        col.addView(titleText("If holding Power still opens the power menu", 15f, "#112133", bold = true, top = 26))
        col.addView(bodyText(
            "Make sure steps 2 and 3 above are done — step 3 is the phone's own " +
                "hold-Power switch, which needs to point at the assistant:\n\n" +
                "• Pixel / stock: Settings › System › Gestures › Power button › hold for Assistant\n" +
                "• Samsung: Settings › Advanced features › Side key\n" +
                "• Xiaomi/Redmi: Settings › Apps › Default apps › Assist & voice input\n" +
                "• Then also disable any live wallpaper's “tap/power gestures”: Settings › Wallpaper & style",
            top = 8
        ))

        val oem = SpannableClick("Open Acazen's app settings (battery: set to Unrestricted) →") { openAppSettingsPage() }
        val oemLink = TextView(this).apply {
            text = oem.spannable
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            setTextColor(Color.parseColor("#1A73E8"))
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        }
        col.addView(oemLink)

        return bg
    }

    private class SpannableClick(text: String, onClick: () -> Unit) {
        val spannable = android.text.SpannableString(text).apply {
            setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) = onClick()
            }, 0, text.length, 0)
        }
    }

    private fun onPrimary() {
        when {
            !micGranted() -> requestMic()
            !assistantOn() -> requestAssistantRole()
            !DoublePressPrefs.isGestureConfirmed(this) -> openGestureGuide()
            !testedOkOnce -> runLiveTest()
            else -> toast("You're all set — try holding Power now 🎉")
        }
    }

    private fun stepRow(num: String, title: String, desc: String, pillView: TextView, onClick: () -> Unit): View {
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
        row.addView(pillView, pl)
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
        return TextView(this).apply {
            text = t
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(color))
            layoutParams = lp(w = -1, h = -2, top = top)
        }
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
        val assistant = assistantOn()

        setPill(micPill, if (mic) "Done ✓" else "Needed",
            if (mic) "#E6F4EA" else "#FDECEC", if (mic) "#137333" else "#C5221F", animated && mic)
        setPill(assistantPill, if (assistant) "On ✓" else "Off",
            if (assistant) "#E6F4EA" else "#EEF2F7", if (assistant) "#137333" else "#5A7A9A", animated && assistant)
        val gesture = DoublePressPrefs.isGestureConfirmed(this)
        setPill(gesturePill, if (gesture) "Done ✓" else "To do",
            if (gesture) "#E6F4EA" else "#EEF2F7", if (gesture) "#137333" else "#5A7A9A", animated && gesture)
        setPill(testPill, if (testedOkOnce) "Tried ✓" else "Ready",
            "#E6F4EA", "#137333", animate = false)

        DoublePressPrefs.setEnabled(this, mic && assistant)

        primaryButton.text = when {
            !mic -> "Allow microphone"
            !assistant -> "Make Acazen my Assistant"
            !gesture -> "Turn on hold-Power for Assistant"
            !testedOkOnce -> "Try it: open the listening overlay"
            else -> "All set — hold Power to try it"
        }

        if (mic && assistant) {
            if (animated && !announcedReady) {
                announcedReady = true
                toast("✅ Hold-Power is ON — press and HOLD Power anywhere to speak to Acazen")
            }
        } else {
            announcedReady = false
        }
    }

    private fun setPill(pillView: TextView, text: String, bgHex: String, fgHex: String, animate: Boolean) {
        val changed = pillView.text != text
        pillView.text = text
        pillView.setBackgroundColor(Color.parseColor(bgHex))
        pillView.setTextColor(Color.parseColor(fgHex))
        if (animate && changed) {
            pillView.scaleX = 0.6f
            pillView.scaleY = 0.6f
            pillView.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(
                android.view.animation.OvershootInterpolator()
            ).start()
        }
    }
}