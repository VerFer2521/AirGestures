package com.project_by_adr.airgestures

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.exp
import kotlin.math.hypot
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * The floating panel shown over other apps: dark glass, grey border that turns
 * neon when a hand is found, with the hand skeleton, a hold ring that travels
 * around the border, and action pills.
 */
class HandSkeletonOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        // Needed so BlurMaskFilter (the glow) actually renders
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val density = resources.displayMetrics.density
    private val uiHandler = Handler(Looper.getMainLooper())

    // ---- state ----
    // hand points: where the AI says they are (target) and where we draw them (shown)
    private val targetX = FloatArray(21)
    private val targetY = FloatArray(21)
    private val shownX = FloatArray(21)
    private val shownY = FloatArray(21)
    private var hasShown = false
    private var smoothing = false
    private var lastFrameNanos = 0L

    // smoothing strength: seconds it takes to close most of the gap to the AI's position
    private val slowTau = 0.07f   // hand almost still: smooth out jitter
    private val fastTau = 0.02f   // hand moving fast: follow almost immediately
    private var label: String? = null
    private var paused = false
    private var handPresent = false
    private var holdProgress = 0f

    // ---- animated values ----
    private var handMix = 0f      // 0 = grey border, 1 = neon border
    private var breath = 1f       // idle breathing of the border
    private var labelIn = 0f      // 0 = hidden, 1 = fully shown (can overshoot)
    private var flash = 0f        // brief border flash when an action fires

    private var handMixAnim: ValueAnimator? = null
    private var breathAnim: ValueAnimator? = null
    private var labelAnim: ValueAnimator? = null
    private var flashAnim: ValueAnimator? = null

    // ---- colours ----
    private val neonCyan = Color.parseColor("#00E5FF")
    private val neonMagenta = Color.parseColor("#FF2E92")
    private val greyBorder = Color.argb(150, 190, 198, 208)     // grey, see-through
    private val neonBorder = Color.argb(235, 0, 229, 255)

    // ---- shapes ----
    private val panelRect = RectF()
    private val pillRect = RectF()
    private val ringPath = Path()
    private val segPath = Path()
    private val measure = PathMeasure()

    // ---- paints ----
    private val panelPaint = Paint().apply {
        color = Color.parseColor("#990B0F14")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        isAntiAlias = true
    }

    private val flashPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val ringGlowPaint = Paint().apply {
        color = neonCyan
        style = Paint.Style.STROKE
        strokeWidth = 8f * density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        alpha = 110
        maskFilter = BlurMaskFilter(10f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val ringPaint = Paint().apply {
        color = neonCyan
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val glowLinePaint = Paint().apply {
        color = neonCyan
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    }

    private val linePaint = Paint().apply {
        color = neonCyan
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val glowJointPaint = Paint().apply {
        color = neonMagenta
        style = Paint.Style.FILL
        isAntiAlias = true
        maskFilter = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
    }

    private val jointPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val jointRingPaint = Paint().apply {
        color = neonMagenta
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    private val labelBgPaint = Paint().apply {
        color = Color.parseColor("#D9000000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val labelBorderPaint = Paint().apply {
        color = neonCyan
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        isAntiAlias = true
    }

    private val idlePaint = Paint().apply {
        color = Color.parseColor("#9AA6B2")
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val connections = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16,
        13 to 17, 17 to 18, 18 to 19, 19 to 20,
        0 to 17
    )

    // Fingertip indices get a bigger glowing joint
    private val fingertips = setOf(4, 8, 12, 16, 20)

    private val hideLabel = Runnable { animateLabelOut() }

    // ------------------------------------------------------------ public API

    fun updateLandmarks(newLandmarks: List<NormalizedLandmark>) {
        if (newLandmarks.size < 21) return
        for (i in 0 until 21) {
            targetX[i] = newLandmarks[i].x()
            targetY[i] = newLandmarks[i].y()
        }
        if (!hasShown) {
            // hand just appeared: start exactly on it instead of sliding in from nowhere
            for (i in 0 until 21) {
                shownX[i] = targetX[i]
                shownY[i] = targetY[i]
            }
            hasShown = true
        }
        setHandPresent(true)
        startSmoothing()
        postInvalidate()
    }

    fun clear() {
        hasShown = false
        stopSmoothing()
        setHandPresent(false)
        postInvalidate()
    }

    /** 0..1: how far the current gesture's hold has got. Drawn around the border. */
    fun setHold(progress: Float) {
        val p = if (paused) 0f else progress
        if (p == holdProgress) return
        holdProgress = p
        postInvalidate()
    }

    fun setPaused(value: Boolean) {
        paused = value
        if (value) holdProgress = 0f
        postInvalidate()
    }

    /** Shows a short message (e.g. "⏭ Next track") as a pill for about 1.4 seconds. */
    fun showLabel(text: String) {
        label = if (text.length > 24) text.take(23) + "…" else text

        // pill slides up with a little bounce
        labelAnim?.cancel()
        labelAnim = ValueAnimator.ofFloat(labelIn.coerceAtMost(1f), 1f).apply {
            duration = 260
            interpolator = OvershootInterpolator(1.8f)
            addUpdateListener {
                labelIn = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        // the border flashes
        flashAnim?.cancel()
        flashAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 550
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                flash = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        uiHandler.removeCallbacks(hideLabel)
        uiHandler.postDelayed(hideLabel, 1400)
    }

    /** Shrinks and fades the panel away, then calls onDone (used before removing the window). */
    fun playExit(onDone: () -> Unit) {
        uiHandler.removeCallbacks(hideLabel)
        animate()
            .alpha(0f)
            .scaleX(0.85f).scaleY(0.85f)
            .translationY(40f * density)
            .setStartDelay(0)
            .setDuration(250)
            .setInterpolator(AccelerateInterpolator())
            .start()
        // do not rely on the animation callback: always finish
        uiHandler.postDelayed({ onDone() }, 290)
    }

    // ------------------------------------------------------------- smoothing

    /**
     * The AI reports hand positions maybe 20-30 times a second, but the screen
     * redraws 60+ times a second. Between reports we glide the drawn points
     * towards the newest position, every screen frame.
     *
     * The glide is adaptive (the idea behind the "One Euro" filter): when the
     * hand is nearly still the points move slowly, which removes shaking; when
     * the hand moves fast they catch up almost instantly, so there is no lag.
     * It switches itself off when the points have arrived, so it costs nothing
     * while the hand is perfectly still.
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            smoothing = false
            if (!hasShown) return

            val dt = if (lastFrameNanos == 0L) 1f / 60f
            else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNanos = frameTimeNanos

            var biggestGap = 0f
            for (i in 0 until 21) {
                val dx = targetX[i] - shownX[i]
                val dy = targetY[i] - shownY[i]
                val gap = hypot(dx, dy)

                // 0 = almost still, 1 = moving fast
                val speed = ((gap - 0.002f) / 0.028f).coerceIn(0f, 1f)
                val tau = slowTau + (fastTau - slowTau) * speed
                val k = 1f - exp(-dt / tau)

                shownX[i] += dx * k
                shownY[i] += dy * k
                if (gap > biggestGap) biggestGap = gap
            }
            invalidate()

            if (biggestGap > 0.0004f) startSmoothing() else lastFrameNanos = 0L
        }
    }

    private fun startSmoothing() {
        if (smoothing) return
        smoothing = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopSmoothing() {
        smoothing = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        lastFrameNanos = 0L
    }

    // -------------------------------------------------------------- animation

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // entrance: slides up, grows and fades in with a bounce
        alpha = 0f
        scaleX = 0.85f
        scaleY = 0.85f
        translationY = 60f * density
        animate()
            .alpha(1f)
            .scaleX(1f).scaleY(1f)
            .translationY(0f)
            .setStartDelay(0)
            .setDuration(450)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()
        startBreathing()
    }

    override fun onDetachedFromWindow() {
        stopSmoothing()
        handMixAnim?.cancel()
        breathAnim?.cancel()
        labelAnim?.cancel()
        flashAnim?.cancel()
        uiHandler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun setHandPresent(present: Boolean) {
        if (present == handPresent) return
        handPresent = present

        handMixAnim?.cancel()
        handMixAnim = ValueAnimator.ofFloat(handMix, if (present) 1f else 0f).apply {
            duration = 300
            addUpdateListener {
                handMix = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        // breathe only while idle, so we don't redraw needlessly
        if (present) {
            breathAnim?.cancel()
            breath = 1f
        } else {
            startBreathing()
        }
    }

    private fun startBreathing() {
        if (handPresent) return
        breathAnim?.cancel()
        breathAnim = ValueAnimator.ofFloat(0.35f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                breath = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animateLabelOut() {
        labelAnim?.cancel()
        labelAnim = ValueAnimator.ofFloat(labelIn, 0f).apply {
            duration = 220
            addUpdateListener {
                labelIn = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (labelIn <= 0.01f) label = null
                }
            })
            start()
        }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val margin = 6f * density
        val radius = 26f * density
        panelRect.set(margin, margin, w - margin, h - margin)

        // 1. glass panel
        canvas.drawRoundRect(panelRect, radius, radius, panelPaint)

        // 2. border: grey, turning neon when a hand is found; breathes when idle
        val mix = if (paused) 0f else handMix
        val base = ColorUtils.blendARGB(greyBorder, neonBorder, mix)
        borderPaint.color = base
        val idleFactor = if (handPresent || paused) 1f else breath
        borderPaint.alpha = (Color.alpha(base) * idleFactor).toInt()
        canvas.drawRoundRect(panelRect, radius, radius, borderPaint)

        // 3. flash when an action fires
        if (flash > 0.01f) {
            flashPaint.alpha = (flash * 230).toInt()
            flashPaint.strokeWidth = (2f + 5f * flash) * density
            canvas.drawRoundRect(panelRect, radius, radius, flashPaint)
        }

        // 4. hold ring travelling around the border
        if (holdProgress > 0.02f) {
            ringPath.reset()
            ringPath.addRoundRect(panelRect, radius, radius, Path.Direction.CW)
            measure.setPath(ringPath, false)
            segPath.reset()
            measure.getSegment(0f, measure.length * holdProgress, segPath, true)
            canvas.drawPath(segPath, ringGlowPaint)
            canvas.drawPath(segPath, ringPaint)
        }

        // 5. hand skeleton, kept in the same proportions as the camera picture
        if (hasShown) drawSkeleton(canvas)

        // 6. idle text, paused pill, action pill
        if (!handPresent && !paused) {
            idlePaint.alpha = (150 * (1f - handMix) * idleFactor).toInt()
            canvas.drawText("Show your hand", w / 2f, h / 2f + 4f * density, idlePaint)
        }
        if (paused) {
            drawPill(canvas, "⏸  PAUSED", panelRect.top + 30f * density, 1f)
        }
        label?.let {
            val rise = (1f - labelIn) * 14f * density
            drawPill(canvas, it, panelRect.bottom - 20f * density + rise, labelIn.coerceIn(0f, 1f))
        }
    }

    private fun drawSkeleton(canvas: Canvas) {
        val dim = if (paused) 0.35f else 1f
        val pad = 16f * density
        val boxH = panelRect.height() - 2f * pad
        val boxW = boxH * 0.75f                     // camera picture is portrait, about 3:4
        val left = (width - boxW) / 2f
        val top = panelRect.top + pad

        // mirrored horizontally to match the front camera's natural view
        fun px(i: Int) = Pair(left + (1f - shownX[i]) * boxW, top + shownY[i] * boxH)

        glowLinePaint.alpha = (110 * dim).toInt()
        linePaint.alpha = (255 * dim).toInt()

        // Pass 1: glow layer for lines
        for ((startIdx, endIdx) in connections) {
            val (sx, sy) = px(startIdx)
            val (ex, ey) = px(endIdx)
            canvas.drawLine(sx, sy, ex, ey, glowLinePaint)
        }
        // Pass 2: crisp line on top
        for ((startIdx, endIdx) in connections) {
            val (sx, sy) = px(startIdx)
            val (ex, ey) = px(endIdx)
            canvas.drawLine(sx, sy, ex, ey, linePaint)
        }
        // Pass 3: joints (bigger + glowing at fingertips)
        glowJointPaint.alpha = (130 * dim).toInt()
        jointPaint.alpha = (255 * dim).toInt()
        jointRingPaint.alpha = (255 * dim).toInt()
        for (index in 0 until 21) {
            val (x, y) = px(index)
            val isTip = fingertips.contains(index)
            val r = if (isTip) 9f else 5f
            if (isTip) canvas.drawCircle(x, y, r + 7f, glowJointPaint)
            canvas.drawCircle(x, y, r, jointPaint)
            canvas.drawCircle(x, y, r, jointRingPaint)
        }
    }

    /** A rounded pill with text, centred horizontally. alpha is 0..1. */
    private fun drawPill(canvas: Canvas, text: String, baseline: Float, alpha: Float) {
        val a = alpha.coerceIn(0f, 1f)
        if (a <= 0f) return
        val padH = 14f * density
        val padV = 8f * density
        val textW = labelPaint.measureText(text)
        val fm = labelPaint.fontMetrics
        val cx = width / 2f

        pillRect.set(
            cx - textW / 2f - padH,
            baseline + fm.ascent - padV,
            cx + textW / 2f + padH,
            baseline + fm.descent + padV
        )
        val r = pillRect.height() / 2f

        labelBgPaint.alpha = (217 * a).toInt()
        labelBorderPaint.alpha = (255 * a).toInt()
        labelPaint.alpha = (255 * a).toInt()
        canvas.drawRoundRect(pillRect, r, r, labelBgPaint)
        canvas.drawRoundRect(pillRect, r, r, labelBorderPaint)
        canvas.drawText(text, cx, baseline, labelPaint)
    }
}