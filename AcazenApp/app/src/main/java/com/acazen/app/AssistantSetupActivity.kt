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
import android.util.Log
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
 * Setup page for hold-Power voice shortcut.
 * Step 2 requests RoleManager.ROLE_ASSISTANT so the OS routes
 * hold-Power to AcazenInteractionSession.
 */
class AssistantSetupActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AssistantSetup"
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, AssistantSetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private lateinit var micPill: TextView
    private lateinit var assistantPill: TextView
    private lateinit var testPill: TextView
    private lateinit var primaryButton: Button
    private lateinit var stepList: LinearLayout
    private var announcedReady = false
    private var testedOkOnce = false

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh(animated = true) }

    // FIX: auto-fallback when system returns without granting.
    // Previously this just called refresh(), so silent-cancel looked like a dead click.
    private val roleRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refresh(animated = true)
            if (!assistantOn()) {
                toast("Pick Acazen on the next screen")
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
        // Re-read role + permission fresh - user may have changed them in Settings.
        refresh(animated = true)
    }

    // ---- state ----

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

    // ---- actions ----

    private fun requestMic() = micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

    /**
     * FIX: Step 2 handler. Proves click fired, checks OEM support,
     * then falls back to manual Settings when dialog is missing/dropped.
     */
    private fun requestAssistantRole() {
        Log.d(TAG, "step2 clicked")
        toast("Opening Assistant settings...")

        val rm = roleManager()
        if (rm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !roleAvailable()) {
            Log.d(TAG, "role not available, opening manual settings")
            openVoiceInputSettings()
            return
        }
        try {
            val intent = rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            // Some OEMs have no activity for the role intent - go manual immediately.
            if (intent.resolveActivity(packageManager) == null) {
                Log.d(TAG, "no handler for role intent, opening manual settings")
                openVoiceInputSettings()
                return
            }
            roleRequestLauncher.launch(intent)
        } catch (e: Exception) {
            Log.d(TAG, "role request failed, opening manual settings: $e")
            openVoiceInputSettings()
        }
    }

    /** Manual path: Default apps > Assist & voice input */
    private fun openVoiceInputSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                toast("Open Settings › Apps › Default apps › Assist & voice input, then choose Acazen")
            } catch (ignored: Exception) {
                toast("Open Settings › Apps › Default apps › Assist & voice input, then choose Acazen")
            }
        }
    }

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
        refresh(animated = false)
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

    // ---- UI ----

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

        col.addView(titleText("Set up in 3 steps", 15f, "#112133", bold = true, top = 26))

        micPill = pill("Not done", "#FDECEC", "#C5221F")
        assistantPill = pill("Off", "#EEF2F7", "#5A7A9A")
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
                "3", "Try the overlay",
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

        col.addView(titleText("How it works", 15f, "#112133", bold = true, top = 30))
        col.addView(bodyText(
            "① HOLD the Power button, same as you would for a phone's built-in assistant.\n" +
                "② Acazen appears full-screen with a pulsing mic and writes what it hears.\n" +
                "③ When you stop talking, the question is sent to Acazen by itself.",
            top = 8
        ))

        col.addView(titleText("If holding Power still opens the power menu", 15f, "#112133", bold = true, top = 26))
        col.addView(bodyText(
            "Make sure step 2 above shows “On”. Some phones also have their own " +
                "assistant-gesture switch that needs to point at Acazen:\n\n" +
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
            isClickable = true
            isFocusable = true
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

    // ---- live state sync ----

    private fun refresh(animated: Boolean) {
        val mic = micGranted()
        val assistant = assistantOn()

        setPill(micPill, if (mic) "Done ✓" else "Needed",
            if (mic) "#E6F4EA" else "#FDECEC", if (mic) "#137333" else "#C5221F", animated && mic)
        setPill(assistantPill, if (assistant) "On ✓" else "Off",
            if (assistant) "#E6F4EA" else "#EEF2F7", if (assistant) "#137333" else "#5A7A9A", animated && assistant)
        setPill(testPill, if (testedOkOnce) "Tried ✓" else "Ready",
            if (testedOkOnce) "#E6F4EA" else "#EEF2F7",
            if (testedOkOnce) "#137333" else "#5A7A9A", animate = false)

        DoublePressPrefs.setEnabled(this, mic && assistant)

        primaryButton.text = when {
            !mic -> "Allow microphone"
            !assistant -> "Make Acazen my Assistant"
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

    // FIX: preserve rounded corners - old version used setBackgroundColor() which wiped them.
    private fun setPill(pillView: TextView, text: String, bgHex: String, fgHex: String, animate: Boolean) {
        val changed = pillView.text != text
        pillView.text = text
        pillView.background = rounded(bgHex, 12)
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
