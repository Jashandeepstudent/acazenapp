package com.acazen.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The listening / processing screen for the hold-Power voice shortcut: soft floating dust
 * particles with real depth-of-field drifting around the Acazen mark, plus a light sweep that
 * runs across the mark's own shape. Calm while listening, brisker while thinking.
 *
 * Shared by both hosts of the voice flow — AcazenInteractionSession (the real gesture) and
 * VoiceCommandActivity (the "Try it" rehearsal) — so the two always look identical.
 *
 * Colors follow the app's light-only rule (see Theme.Acazen): this overlay never goes dark.
 */
class ListeningOverlay(context: Context) {

    /** The full-screen view to hand to setContentView() / onCreateContentView(). */
    val root: FrameLayout = FrameLayout(context)

    private val animation = ListeningAnimationView(context)
    private val caption = TextView(context)
    private val sub = TextView(context)

    private var heard = ""
    private var hint = ""

    init {
        val dm = context.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).roundToInt()

        // Soft blue, brightest near the top. A solid base sits under the radial glow so the
        // screen is blue even if a device draws the gradient layer wrongly (never black).
        val top = ContextCompat.getColor(context, R.color.voice_bg_top)
        val bottom = ContextCompat.getColor(context, R.color.voice_bg_bottom)
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(top, bottom)
            gradientRadius = dm.heightPixels.toFloat()
            setGradientCenter(0.5f, 0.16f)
        }
        root.background = LayerDrawable(arrayOf(ColorDrawable(bottom), glow))
        root.fitsSystemWindows = false

        caption.apply {
            textSize = 16f
            setTextColor(0xFF0F2E8C.toInt())
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        sub.apply {
            textSize = 13f
            setTextColor(0xFF5C6B88.toInt())
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        animation.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), 0, dp(32), 0)
        }
        column.addView(
            animation,
            LinearLayout.LayoutParams(animation.stagePx, animation.stagePx)
        )
        column.addView(
            caption,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(22) }
        )
        column.addView(
            sub,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )
        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        showListening()
    }

    /** Calm drift + gentle bob; the recognizer is waiting for / hearing the user. */
    fun showListening() {
        animation.state = ListeningAnimationView.State.LISTENING
        caption.text = "Listening…"
        hint = "say anything"
        refreshSub()
    }

    /** Live transcript while the user is speaking (shown under the caption). */
    fun showHeard(text: String) {
        heard = text
        refreshSub()
    }

    /** Brisk drift + wobble + fast sweep; speech is being recognized / handed off to Acazen. */
    fun showThinking(subtitle: String = "") {
        animation.state = ListeningAnimationView.State.PROCESSING
        caption.text = "Thinking…"
        hint = subtitle
        refreshSub()
    }

    /** One-off message in the caption slot (errors, rehearsal results). Animation stays calm. */
    fun showMessage(text: String) {
        animation.state = ListeningAnimationView.State.LISTENING
        caption.text = text
        heard = ""
        hint = ""
        refreshSub()
    }

    private fun refreshSub() {
        val t = if (heard.isNotEmpty()) heard else hint
        sub.text = t
        sub.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }
}

/**
 * Canvas port of the approved "Realistic Particles + Logo Shimmer" preview.
 *
 *  - 42 dust motes on a 240dp stage (200dp on narrow phones). Each has a depth: far ones are
 *    smaller, dimmer, slower and blurrier; near ones are larger, brighter, faster and get a tiny
 *    white core. They drift gently upward, sway, and twinkle.
 *  - The Acazen mark bobs while listening and wobbles while thinking, with a soft drop shadow.
 *  - A light band sweeps across the mark, masked to the mark's own alpha.
 *
 * Particle glows are pre-rendered once into small bitmaps (blur included) so each frame is just
 * a handful of drawBitmap calls — no per-frame allocation and no software-only blur filters.
 */
class ListeningAnimationView(context: Context) : View(context) {

    enum class State { LISTENING, PROCESSING }

    /** Switching state eases between the calm and brisk looks instead of jumping. */
    var state: State = State.LISTENING

    private val density = resources.displayMetrics.density
    private val narrow = resources.configuration.screenWidthDp <= 380
    private val stageDp = if (narrow) 200f else 240f
    private val logoWidthDp = if (narrow) 108f else 128f

    /** Side of the (square) stage in pixels — the host uses it for the layout params. */
    val stagePx: Int = (stageDp * density).roundToInt()

    private val reduceMotion: Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    } catch (e: Exception) {
        false
    }

    // ---- particles -----------------------------------------------------------------------

    private class Particle(
        val z: Float,
        var x: Float,
        var y: Float,
        val baseR: Float,
        val alphaBase: Float,
        val vx: Float,
        val vy: Float,
        var sway: Float,
        val swaySpeed: Float,
        val swayAmp: Float,
        var twinklePhase: Float,
        val twinkleSpeed: Float,
        val sprite: Bitmap,
        val spriteHalf: Float
    ) {
        var baseX: Float = x
    }

    private val particles = ArrayList<Particle>()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // white, brand-100, brand-200, brand-300 (rgb only)
    private val tints = intArrayOf(0xFFFFFF, 0xD7E4FF, 0xB7CDFF, 0x8FB0FF)

    // ---- logo ----------------------------------------------------------------------------

    private var logo: Bitmap? = null
    private var logoShadow: Bitmap? = null
    private var shadowDx = 0f
    private var shadowDy = 0f
    private var logoW = 0f
    private var logoH = 0f
    private var logoLeft = 0f
    private var logoTop = 0f

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24_10192E } // 14% ink
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sweepShader: LinearGradient? = null
    private val sweepMatrix = Matrix()
    private val logoBounds = RectF()

    // ---- animation clock -----------------------------------------------------------------

    private val easeInOut = PathInterpolator(0.42f, 0f, 0.58f, 1f)
    private var lastTime = 0L
    private var mix = 0f          // 0 = listening look, 1 = processing look
    private var bobPhase = 0f
    private var wobblePhase = 0f
    private var sweepPhase = 0f

    init {
        seedParticles()
        buildLogo()
    }

    // ===== setup ============================================================================

    private fun rand(min: Float, max: Float) = min + Random.nextFloat() * (max - min)

    private fun seedParticles() {
        particles.clear()
        for (i in 0 until PARTICLE_COUNT) {
            // depth 0 (far/small/dim/slow) .. 1 (near/large/bright/fast)
            val z = rand(0f, 1f)
            val speed = 0.10f + z * 0.30f
            val angle = rand(0f, (2 * PI).toFloat())
            val baseR = rand(1.1f, 3.2f) * (0.45f + z * 0.95f)
            val blur = (1f - z) * 2.4f + 0.3f
            val sprite = makeGlowSprite(tints[Random.nextInt(tints.size)], baseR, blur)
            particles.add(
                Particle(
                    z = z,
                    x = rand(0f, stageDp),
                    y = rand(0f, stageDp),
                    baseR = baseR,
                    alphaBase = 0.18f + z * 0.55f,
                    // gentle convective drift, slight upward bias like dust in light
                    vx = cos(angle) * speed * 0.35f,
                    vy = -abs(sin(angle)) * speed * 0.55f - speed * 0.18f,
                    sway = rand(0f, (2 * PI).toFloat()),
                    swaySpeed = rand(0.25f, 0.55f),
                    swayAmp = rand(4f, 14f) * (0.4f + z * 0.8f),
                    twinklePhase = rand(0f, (2 * PI).toFloat()),
                    twinkleSpeed = rand(0.5f, 1.3f),
                    sprite = sprite,
                    spriteHalf = sprite.width / 2f
                )
            )
        }
        // far-to-near so nearer motes paint on top, like real bokeh
        particles.sortBy { it.z }
    }

    /**
     * Soft glow disc: alpha 0.95 at the centre, 0.55 at 45% of the radius, 0 at the edge — then
     * Gaussian-blurred by [blurDp] (far motes are blurrier). Stored as the tint with a varying
     * alpha channel; the per-frame paint alpha supplies brightness and twinkle.
     */
    private fun makeGlowSprite(rgb: Int, rDp: Float, blurDp: Float): Bitmap {
        val glowR = rDp * 2.6f * density
        val sigma = if (blurDp > 0.4f) blurDp * density else 0f
        val pad = ceil(sigma * 3f).toInt()
        val half = ceil(glowR).toInt() + pad + 1
        val size = half * 2 + 1

        var a = FloatArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val d = hypot((x - half).toFloat(), (y - half).toFloat()) / glowR
                a[y * size + x] = when {
                    d >= 1f -> 0f
                    d <= 0.45f -> 0.95f + (0.55f - 0.95f) * (d / 0.45f)
                    else -> 0.55f * (1f - (d - 0.45f) / 0.55f)
                }
            }
        }

        if (sigma > 0f) {
            val k = ceil(sigma * 3f).toInt()
            val kernel = FloatArray(2 * k + 1)
            var sum = 0f
            for (i in -k..k) {
                val w = exp(-(i * i) / (2f * sigma * sigma))
                kernel[i + k] = w
                sum += w
            }
            for (i in kernel.indices) kernel[i] /= sum

            val tmp = FloatArray(size * size)
            for (y in 0 until size) {            // horizontal pass
                for (x in 0 until size) {
                    var acc = 0f
                    for (i in -k..k) {
                        val xx = x + i
                        if (xx in 0 until size) acc += a[y * size + xx] * kernel[i + k]
                    }
                    tmp[y * size + x] = acc
                }
            }
            val out = FloatArray(size * size)
            for (y in 0 until size) {            // vertical pass
                for (x in 0 until size) {
                    var acc = 0f
                    for (i in -k..k) {
                        val yy = y + i
                        if (yy in 0 until size) acc += tmp[yy * size + x] * kernel[i + k]
                    }
                    out[y * size + x] = acc
                }
            }
            a = out
        }

        val pixels = IntArray(size * size)
        for (i in pixels.indices) {
            val alpha = (a[i] * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = (alpha shl 24) or (rgb and 0xFFFFFF)
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    private fun buildLogo() {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        val src = BitmapFactory.decodeResource(resources, R.drawable.acazen_mark, opts) ?: return

        logoW = logoWidthDp * density
        logoH = logoW * src.height / src.width
        val scaled = Bitmap.createScaledBitmap(src, logoW.roundToInt(), logoH.roundToInt(), true)
        if (scaled !== src) src.recycle()
        logo = scaled
        logoLeft = (stagePx - scaled.width) / 2f
        logoTop = (stagePx - scaled.height) / 2f
        logoW = scaled.width.toFloat()
        logoH = scaled.height.toFloat()

        // drop-shadow(0 8px 20px rgba(16,25,46,.14)): blurred copy of the mark's alpha.
        val sigmaPx = 10f * density
        val blurPaint = Paint().apply {
            maskFilter = BlurMaskFilter(max(1f, (sigmaPx - 0.5f) / 0.57735f), BlurMaskFilter.Blur.NORMAL)
        }
        val offset = IntArray(2)
        logoShadow = scaled.extractAlpha(blurPaint, offset)
        shadowDx = offset[0].toFloat()
        shadowDy = offset[1].toFloat() + 8f * density

        // linear-gradient(100deg, transparent 30%, #b7cdff/.95 48%, white/.98 52%, #b7cdff/.95 56%,
        // transparent 70%) laid out on an image 260% as wide as the mark, like the preview's CSS.
        val imgW = logoW * 2.6f
        val imgH = logoH
        val theta = Math.toRadians(100.0)
        val dirX = sin(theta).toFloat()
        val dirY = -cos(theta).toFloat()
        val len = abs(imgW * sin(theta).toFloat()) + abs(imgH * cos(theta).toFloat())
        val cx = imgW / 2f
        val cy = imgH / 2f
        val clear = 0x00B7CDFF
        sweepShader = LinearGradient(
            cx - dirX * len / 2f, cy - dirY * len / 2f,
            cx + dirX * len / 2f, cy + dirY * len / 2f,
            intArrayOf(
                clear, clear,
                argb(0.95f, 0xB7CDFF), argb(0.98f, 0xFFFFFF), argb(0.95f, 0xB7CDFF),
                clear, clear
            ),
            floatArrayOf(0f, 0.30f, 0.48f, 0.52f, 0.56f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
        sweepPaint.shader = sweepShader
        logoBounds.set(logoLeft, logoTop, logoLeft + logoW, logoTop + logoH)
    }

    private fun argb(alpha: Float, rgb: Int): Int =
        ((alpha * 255f).roundToInt() shl 24) or (rgb and 0xFFFFFF)

    // ===== view plumbing ====================================================================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(stagePx, stagePx)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastTime = 0L // don't count time spent detached as one giant frame
    }

    // ===== per-frame ========================================================================

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastTime == 0L || reduceMotion) 0f else min(0.05f, (now - lastTime) / 1000f)
        lastTime = now

        val target = if (state == State.PROCESSING) 1f else 0f
        mix += (target - mix) * min(1f, dt * 2.5f)
        val speedMul = 1f + 1.6f * mix // 1 calm … 2.6 brisk, eased like the preview

        bobPhase = (bobPhase + dt / 3.2f) % 1f
        wobblePhase = (wobblePhase + dt / 1.0f) % 1f
        val sweepRate = (1f / 3.2f) + ((1f / 0.8f) - (1f / 3.2f)) * mix
        sweepPhase = (sweepPhase + dt * sweepRate) % 1f

        drawParticles(canvas, dt, speedMul)
        drawLogo(canvas)

        if (!reduceMotion) postInvalidateOnAnimation()
    }

    private fun drawParticles(canvas: Canvas, dt: Float, speedMul: Float) {
        val margin = 20f
        for (p in particles) {
            if (dt > 0f) {
                p.sway += p.swaySpeed * dt * speedMul
                p.baseX += p.vx * dt * speedMul * 14f
                p.y += p.vy * dt * speedMul * 14f
                p.x = p.baseX + sin(p.sway) * p.swayAmp
                p.twinklePhase += p.twinkleSpeed * dt * speedMul

                // wrap with a margin so motes keep recycling
                if (p.y < -margin) {
                    p.y = stageDp + margin
                    p.baseX = rand(0f, stageDp)
                    p.x = p.baseX
                }
                if (p.baseX < -margin) p.baseX = stageDp + margin
                if (p.baseX > stageDp + margin) p.baseX = -margin
            }

            val twinkle = 0.75f + 0.25f * sin(p.twinklePhase)
            val alpha = p.alphaBase * twinkle * edgeFade(p.x, p.y)
            if (alpha <= 0.004f) continue

            glowPaint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
            canvas.drawBitmap(
                p.sprite,
                p.x * density - p.spriteHalf,
                p.y * density - p.spriteHalf,
                glowPaint
            )

            // tiny bright core on the nearer (larger) motes
            if (p.z > 0.55f) {
                corePaint.alpha = (alpha * 0.9f * 255f).roundToInt().coerceIn(0, 255)
                canvas.drawCircle(
                    p.x * density, p.y * density,
                    max(0.4f, p.baseR * 0.32f) * density, corePaint
                )
            }
        }
    }

    /** Motes fade out toward the stage edge so they never pop at the view bounds. */
    private fun edgeFade(x: Float, y: Float): Float {
        val d = min(min(x, stageDp - x), min(y, stageDp - y))
        val t = (d / 28f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun pingPong(phase: Float): Float {
        val u = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
        return easeInOut.getInterpolation(u)
    }

    private fun drawLogo(canvas: Canvas) {
        val bmp = logo ?: return
        val cx = stagePx / 2f
        val cy = stagePx / 2f

        // listening: bob up 4dp and back; thinking: wobble -3° and scale 1.03 — blended by [mix]
        val bobY = -4f * density * pingPong(bobPhase) * (1f - mix)
        val wob = pingPong(wobblePhase) * mix
        val rotation = -3f * wob
        val scale = 1f + 0.03f * wob

        canvas.save()
        canvas.translate(0f, bobY)
        canvas.rotate(rotation, cx, cy)
        canvas.scale(scale, scale, cx, cy)

        logoShadow?.let {
            canvas.drawBitmap(it, logoLeft + shadowDx, logoTop + shadowDy, shadowPaint)
        }
        canvas.drawBitmap(bmp, logoLeft, logoTop, logoPaint)

        if (!reduceMotion) drawSweep(canvas, bmp)
        canvas.restore()
    }

    private fun drawSweep(canvas: Canvas, bmp: Bitmap) {
        val shader = sweepShader ?: return

        // 0–55% of each cycle the band travels left→right; the rest of the cycle it rests offscreen.
        val raw = if (sweepPhase < 0.55f) sweepPhase / 0.55f else 1f
        val eased = easeInOut.getInterpolation(raw)
        val u = eased + (raw - eased) * mix // ease-in-out while listening, linear while thinking
        val percent = 140f + (-40f - 140f) * u
        val imgLeft = -1.6f * logoW * (percent / 100f)

        sweepMatrix.setTranslate(logoLeft + imgLeft, logoTop)
        shader.setLocalMatrix(sweepMatrix)
        sweepPaint.shader = shader

        val layer = canvas.saveLayer(logoBounds, null)
        canvas.drawRect(logoBounds, sweepPaint)
        canvas.drawBitmap(bmp, logoLeft, logoTop, maskPaint) // keep the band only where the mark is
        canvas.restoreToCount(layer)
    }

    private companion object {
        const val PARTICLE_COUNT = 42
    }
}
