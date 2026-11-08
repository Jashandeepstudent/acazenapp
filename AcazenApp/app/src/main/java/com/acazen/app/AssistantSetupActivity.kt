package com.acazen.app

import android.Manifest
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

private val BRAND = Color.parseColor("#1A73E8")
private val GREEN = Color.parseColor("#1E9E5A")
private val NAVY = Color.parseColor("#0F2E8C")
private val INK = Color.parseColor("#112133")
private val MUTED = Color.parseColor("#5C6B88")
private val FAINT = Color.parseColor("#8FA0B8")

/**
 * The setup page for Acazen's hold-Power voice shortcut — four animated steps and nothing else:
 *
 *   1. Microphone access
 *   2. Make Acazen the phone's Assistant (RoleManager.ROLE_ASSISTANT) — this is what makes the OS
 *      route a Power-hold to AcazenInteractionSession
 *   3. Turn on "hold Power for Assistant" in the phone's own settings (Android can't read or flip
 *      that switch, so it is a one-tap confirmation)
 *   4. Try the listening screen
 *
 * The first unfinished step is the active one: it pulses and shows its button. Finishing a step
 * draws its check mark, fills the line down to the next step and moves on. State is always
 * re-read from the system in onResume(), never assumed from a tap, so the page can't lie about
 * what is actually switched on.
 *
 * Fallback notes kept from the earlier version: createRequestRoleIntent(ROLE_ASSISTANT) doesn't
 * show a dialog on every OEM build, so when the role is still off after the request returns we
 * open "Default apps › Assist & voice input" directly.
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

    private lateinit var rows: List<StepRow>
    private lateinit var halo: View
    private lateinit var heroBadge: TextView
    private lateinit var subtitle: TextView
    private lateinit var doneButton: Button
    private var haloAnim: ValueAnimator? = null
    private var testedOkOnce = false
    private var allDone = false
    private var firstRefresh = true

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh(animated = true) }

    private val roleRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refresh(animated = true)
            if (!assistantOn()) openVoiceInputSettings()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Soft-blue page right up to the screen edges, dark status/nav icons on top of it.
        val lightBars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = lightBars, navigationBarStyle = lightBars)
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refresh(animated = false)
        playEntrance()
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed the mic permission or the assistant while we were paused in
        // system Settings — re-read everything the moment they come back.
        refresh(animated = true)
        startHalo()
    }

    override fun onPause() {
        stopHalo()
        super.onPause()
    }

    // ---- state ---------------------------------------------------------------------------------

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

    private fun refresh(animated: Boolean) {
        val mic = micGranted()
        val assistant = assistantOn()
        val gesture = DoublePressPrefs.isGestureConfirmed(this)
        val done = booleanArrayOf(mic, assistant, gesture, testedOkOnce)
        val active = done.indexOfFirst { !it }

        rows.forEachIndexed { i, row ->
            val s = when {
                done[i] -> RailView.State.DONE
                i == active -> RailView.State.ACTIVE
                else -> RailView.State.TODO
            }
            row.setState(s, animated)
        }

        DoublePressPrefs.setEnabled(this, mic && assistant)

        val all = active == -1
        if (firstRefresh || all != allDone) {
            applyAllDone(all, animate = animated && !firstRefresh)
            allDone = all
        }
        firstRefresh = false
    }

    // ---- actions -------------------------------------------------------------------------------

    private fun requestMic() = micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

    /**
     * Requests ROLE_ASSISTANT. On builds where the role dialog doesn't appear this still opens
     * something useful; either way the result handler / onResume re-check isRoleHeld().
     */
    private fun requestAssistantRole() {
        val rm = roleManager()
        if (rm == null || !roleAvailable()) {
            openVoiceInputSettings()
            return
        }
        try {
            roleRequestLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
        } catch (e: Exception) {
            openVoiceInputSettings()
        }
    }

    /** "Default apps › Assist & voice input" lets the user pick Acazen directly. */
    private fun openVoiceInputSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            openSystemSettings("Open Settings › Apps › Default apps › Assist & voice input, then choose Acazen")
        }
    }

    private fun openSystemSettings(hint: String = "Open your phone's Settings and search for “power button”") {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(hint)
        }
    }

    /** Opens the exact listening screen the real gesture shows, so the user can check it here. */
    private fun runLiveTest() {
        try {
            VoiceCommandActivity.launchTest(this)
            testedOkOnce = true
        } catch (e: Exception) {
            toast("Couldn't open the listening screen")
        }
    }

    /**
     * Most phones don't turn "hold Power for the assistant" on by themselves, and Android doesn't
     * let an app flip it (or even read it) — so this shows the right Settings path for the
     * phone's brand and asks the user to confirm once it's on.
     */
    private fun openGestureGuide() {
        val brand = Build.MANUFACTURER.orEmpty().lowercase()
        val path = when {
            "samsung" in brand ->
                "Settings › Advanced features › Side button › Press and hold → choose “Wake Bixby”. " +
                    "It opens whichever assistant is your default — Acazen, after step 2."
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

    // ---- UI ------------------------------------------------------------------------------------

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    ContextCompat.getColor(this@AssistantSetupActivity, R.color.voice_bg_top),
                    ContextCompat.getColor(this@AssistantSetupActivity, R.color.voice_bg_bottom)
                )
            )
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            scroll.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(32))
        }
        scroll.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))

        // ---- hero: the Acazen mark on a softly breathing glow ----
        val hero = FrameLayout(this).apply { clipChildren = false }
        halo = View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(90).toFloat()
                setGradientCenter(0.5f, 0.5f)
                colors = intArrayOf(0x331A73E8, 0x001A73E8)
            }
        }
        hero.addView(halo, FrameLayout.LayoutParams(dp(180), dp(180), Gravity.CENTER))
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.acazen_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        hero.addView(logo, FrameLayout.LayoutParams(dp(132), dp(78), Gravity.CENTER))
        heroBadge = TextView(this).apply {
            text = "✓"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN) }
            alpha = 0f
            scaleX = 0f
            scaleY = 0f
        }
        hero.addView(
            heroBadge,
            FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(136)
                topMargin = dp(100)
            }
        )
        col.addView(hero, LinearLayout.LayoutParams(dp(180), dp(150)))

        val title = TextView(this).apply {
            text = "Hold Power to talk to Acazen"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(NAVY)
            gravity = Gravity.CENTER
        }
        col.addView(title, lp(top = 6))

        subtitle = TextView(this).apply {
            textSize = 15f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }
        col.addView(subtitle, lp(top = 8))

        // ---- the four steps ----
        rows = listOf(
            StepRow("1", "Allow the microphone", "So Acazen can hear you.", "Allow", false) {
                if (!micGranted()) requestMic() else toast("Microphone is already on")
            },
            StepRow(
                "2", "Make Acazen your assistant",
                "This lets the Power button open Acazen.", "Set as assistant", false
            ) { requestAssistantRole() },
            StepRow(
                "3", "Turn on hold-Power",
                "Your phone has to send a Power-hold to the assistant.", "Show me how", false
            ) { openGestureGuide() },
            StepRow(
                "4", "Try it",
                "See the listening screen before you use the real thing.", "Try now", true
            ) { runLiveTest() }
        )
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(22))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(26).toFloat()
            }
            elevation = dp(4).toFloat()
        }
        rows.forEach { card.addView(it.view, LinearLayout.LayoutParams(MATCH, WRAP)) }
        col.addView(card, lp(top = 26))

        doneButton = Button(this).apply {
            text = "Done"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isAllCaps = false
            stateListAnimator = null
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                GradientDrawable().apply { setColor(BRAND); cornerRadius = dp(16).toFloat() },
                null
            )
            visibility = View.GONE
            setOnClickListener { finish() }
        }
        col.addView(doneButton, LinearLayout.LayoutParams(MATCH, dp(54)).apply { topMargin = dp(20) })

        return root
    }

    /** Hero, title, card and each step slide up one after another. */
    private fun playEntrance() {
        val parts = ArrayList<View>()
        parts += (heroBadge.parent as View)
        val col = (subtitle.parent as ViewGroup)
        for (i in 0 until col.childCount) {
            val v = col.getChildAt(i)
            if (v !== doneButton && v !== parts[0]) parts += v
        }
        rows.forEach { parts += it.view }

        var delay = 0L
        for (v in parts) {
            v.alpha = 0f
            v.translationY = dp(16).toFloat()
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(delay).setDuration(380)
                .setInterpolator(DecelerateInterpolator()).start()
            delay += 90
        }
    }

    private fun startHalo() {
        if (haloAnim != null) return
        haloAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val s = 0.9f + 0.18f * t
                halo.scaleX = s
                halo.scaleY = s
                halo.alpha = 0.55f + 0.45f * t
            }
            start()
        }
    }

    private fun stopHalo() {
        haloAnim?.cancel()
        haloAnim = null
    }

    /** Swap the subtitle, pop the hero check and reveal "Done" once all four steps are finished. */
    private fun applyAllDone(all: Boolean, animate: Boolean) {
        val text = if (all) "You're all set. Hold Power, then ask a question or say “Open Acazen”."
        else "Four quick steps — about a minute."

        if (animate) {
            subtitle.animate().cancel()
            subtitle.animate().alpha(0f).setDuration(140).withEndAction {
                subtitle.text = text
                subtitle.animate().alpha(1f).setDuration(220).start()
            }.start()
        } else {
            subtitle.text = text
        }

        val target = if (all) 1f else 0f
        if (animate) {
            heroBadge.animate().alpha(target).scaleX(target).scaleY(target)
                .setDuration(if (all) 420L else 200L)
                .setInterpolator(if (all) OvershootInterpolator(2.4f) else DecelerateInterpolator())
                .start()
        } else {
            heroBadge.alpha = target
            heroBadge.scaleX = target
            heroBadge.scaleY = target
        }

        doneButton.animate().cancel()
        if (all) {
            doneButton.visibility = View.VISIBLE
            if (animate) {
                doneButton.alpha = 0f
                doneButton.translationY = dp(12).toFloat()
                doneButton.animate().alpha(1f).translationY(0f)
                    .setStartDelay(250).setDuration(360).start()
            } else {
                doneButton.alpha = 1f
                doneButton.translationY = 0f
            }
        } else {
            doneButton.visibility = View.GONE
        }
    }

    // ---- one step -------------------------------------------------------------------------------

    /**
     * Rail (number / check badge + the line down to the next step) on the left; title, one short
     * line and — only while this is the active step — its button on the right.
     */
    private inner class StepRow(
        num: String,
        titleText: String,
        descText: String,
        actionLabel: String,
        last: Boolean,
        private val onClick: () -> Unit
    ) {
        var state = RailView.State.TODO
            private set
        private var buttonFrac = 0f
        private var buttonAnim: ValueAnimator? = null

        private val rail = RailView(this@AssistantSetupActivity, num, last).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val titleView = TextView(this@AssistantSetupActivity).apply {
            text = titleText
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        private val descView = TextView(this@AssistantSetupActivity).apply {
            text = descText
            textSize = 13.5f
        }
        private val button = Button(this@AssistantSetupActivity).apply {
            text = actionLabel
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isAllCaps = false
            stateListAnimator = null
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            gravity = Gravity.CENTER
            setPadding(dp(22), 0, dp(22), 0)
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                GradientDrawable().apply { setColor(BRAND); cornerRadius = dp(14).toFloat() },
                null
            )
            visibility = View.GONE
            setOnClickListener { onClick() }
        }
        private val content = LinearLayout(this@AssistantSetupActivity).apply {
            orientation = LinearLayout.VERTICAL
            // 13dp puts the title's first line level with the middle of the 34dp badge.
            setPadding(0, dp(13), 0, if (last) 0 else dp(22))
            addView(titleView)
            addView(descView, lp(top = 3))
            addView(button, LinearLayout.LayoutParams(WRAP, 0))
        }

        val view = LinearLayout(this@AssistantSetupActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(rail, LinearLayout.LayoutParams(dp(48), MATCH))
            addView(content, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
            setOnClickListener { if (state != RailView.State.TODO) onClick() }
        }

        init { setState(RailView.State.TODO, animate = false) }

        fun setState(s: RailView.State, animate: Boolean) {
            val changed = s != state
            state = s
            rail.setState(s, animate && changed)
            titleView.setTextColor(
                when (s) {
                    RailView.State.ACTIVE -> NAVY
                    RailView.State.DONE -> INK
                    RailView.State.TODO -> FAINT
                }
            )
            descView.setTextColor(if (s == RailView.State.TODO) FAINT else MUTED)
            showButton(s == RailView.State.ACTIVE, animate && changed)
        }

        private fun showButton(show: Boolean, animate: Boolean) {
            buttonAnim?.cancel()
            val to = if (show) 1f else 0f
            if (!animate) {
                applyButton(to)
                return
            }
            buttonAnim = ValueAnimator.ofFloat(buttonFrac, to).apply {
                duration = 320
                interpolator = DecelerateInterpolator()
                addUpdateListener { applyButton(it.animatedValue as Float) }
                start()
            }
        }

        private fun applyButton(f: Float) {
            buttonFrac = f
            val params = button.layoutParams as LinearLayout.LayoutParams
            params.height = (dp(46) * f).toInt()
            params.topMargin = (dp(12) * f).toInt()
            button.layoutParams = params
            button.alpha = f
            button.visibility = if (f <= 0f) View.GONE else View.VISIBLE
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * The left rail of one step: a numbered badge that pulses while it is the active step, turns into
 * a green badge whose check mark draws itself when the step is done, plus the line down to the
 * next step, which fills green as the step completes.
 */
internal class RailView(
    context: Context,
    private val number: String,
    private val isLast: Boolean
) : View(context) {

    enum class State { TODO, ACTIVE, DONE }

    private val d = resources.displayMetrics.density
    private val badgeR = 17f * d
    private val ringMax = 7f * d

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#D5E3F5")
    }
    private val fillPaint = Paint(trackPaint).apply { color = GREEN }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        color = Color.parseColor("#BFD1E8")
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        color = BRAND
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15f * d
        typeface = Typeface.DEFAULT_BOLD
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val checkPath = Path()
    private val checkPart = Path()
    private var checkLen = 1f

    private var state = State.TODO
    private var checkProgress = 0f
    private var lineProgress = 0f
    private var pop = 1f
    private var pulse = 0f
    private var pulseAnim: ValueAnimator? = null
    private val oneShots = ArrayList<ValueAnimator>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minSide = ((badgeR + ringMax) * 2f).toInt()
        setMeasuredDimension(
            resolveSize(minSide, widthMeasureSpec),
            resolveSize(minSide, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = w / 2f
        checkPath.reset()
        checkPath.moveTo(cx - 0.42f * badgeR, cy + 0.04f * badgeR)
        checkPath.lineTo(cx - 0.12f * badgeR, cy + 0.36f * badgeR)
        checkPath.lineTo(cx + 0.44f * badgeR, cy - 0.30f * badgeR)
        checkLen = PathMeasure(checkPath, false).length
    }

    fun setState(s: State, animate: Boolean) {
        val previous = state
        state = s
        cancelOneShots()
        when (s) {
            State.DONE -> {
                stopPulse()
                if (animate && previous != State.DONE) {
                    checkProgress = 0f
                    lineProgress = 0f
                    pop = 0.7f
                    animateValue(0f, 1f, 420, DecelerateInterpolator()) { checkProgress = it }
                    animateValue(0.7f, 1f, 360, OvershootInterpolator(2.2f)) { pop = it }
                    animateValue(0f, 1f, 520, DecelerateInterpolator(), delay = 260) { lineProgress = it }
                } else {
                    checkProgress = 1f
                    lineProgress = 1f
                    pop = 1f
                }
            }
            State.ACTIVE -> {
                checkProgress = 0f
                lineProgress = 0f
                pop = 1f
                if (animate) animateValue(0.8f, 1f, 360, OvershootInterpolator(2f)) { pop = it }
                startPulse()
            }
            State.TODO -> {
                stopPulse()
                checkProgress = 0f
                lineProgress = 0f
                pop = 1f
            }
        }
        invalidate()
    }

    private fun animateValue(
        from: Float, to: Float, ms: Long, interpolator: TimeInterpolator,
        delay: Long = 0L, onValue: (Float) -> Unit
    ) {
        val a = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            startDelay = delay
            this.interpolator = interpolator
            addUpdateListener {
                onValue(it.animatedValue as Float)
                invalidate()
            }
        }
        oneShots += a
        a.start()
    }

    private fun cancelOneShots() {
        oneShots.forEach { it.cancel() }
        oneShots.clear()
    }

    private fun startPulse() {
        if (pulseAnim != null) return
        pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnim?.cancel()
        pulseAnim = null
        pulse = 0f
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            if (state == State.ACTIVE) startPulse()
        } else {
            stopPulse()
        }
    }

    override fun onDetachedFromWindow() {
        cancelOneShots()
        stopPulse()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = width / 2f

        // The line down to the next step (grey track, green fill once this step is done).
        if (!isLast) {
            val y0 = cy + badgeR + 6f * d
            val y1 = height.toFloat()
            if (y1 > y0) {
                canvas.drawLine(cx, y0, cx, y1, trackPaint)
                if (lineProgress > 0f) {
                    canvas.drawLine(cx, y0, cx, y0 + (y1 - y0) * lineProgress, fillPaint)
                }
            }
        }

        // Soft ring that ripples outwards from the active badge.
        if (state == State.ACTIVE && pulse > 0f) {
            ringPaint.alpha = ((1f - pulse) * 110f).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, badgeR + ringMax * pulse, ringPaint)
        }

        canvas.save()
        canvas.scale(pop, pop, cx, cy)
        when (state) {
            State.DONE -> {
                badgePaint.color = GREEN
                canvas.drawCircle(cx, cy, badgeR, badgePaint)
                if (checkProgress > 0f) {
                    checkPart.reset()
                    PathMeasure(checkPath, false).getSegment(0f, checkLen * checkProgress, checkPart, true)
                    canvas.drawPath(checkPart, checkPaint)
                }
            }
            State.ACTIVE -> {
                badgePaint.color = BRAND
                canvas.drawCircle(cx, cy, badgeR, badgePaint)
                drawNumber(canvas, cx, cy, Color.WHITE)
            }
            State.TODO -> {
                badgePaint.color = Color.WHITE
                canvas.drawCircle(cx, cy, badgeR, badgePaint)
                canvas.drawCircle(cx, cy, badgeR - outlinePaint.strokeWidth / 2f, outlinePaint)
                drawNumber(canvas, cx, cy, FAINT)
            }
        }
        canvas.restore()
    }

    private fun drawNumber(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        numberPaint.color = color
        val baseline = cy - (numberPaint.descent() + numberPaint.ascent()) / 2f
        canvas.drawText(number, cx, baseline, numberPaint)
    }
}
