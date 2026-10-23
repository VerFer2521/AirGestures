package com.project_by_adr.airgestures

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/** An open hand, drawn as a neon skeleton that traces itself, then pulses. */
class SplashHandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        // needed so the glow (BlurMaskFilter) renders
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val density = resources.displayMetrics.density
    private val neonCyan = Color.parseColor("#00E5FF")
    private val neonMagenta = Color.parseColor("#FF2E92")

    private var reveal = 0f   // 0..1, how much of the hand is drawn
    private var glow = 1f     // gentle pulse after it is drawn

    // 21 hand points as (x, y) between 0 and 1: wrist, thumb, index, middle, ring, pinky
    private val pts = floatArrayOf(
        0.50f, 0.95f,
        0.38f, 0.86f, 0.28f, 0.74f, 0.20f, 0.63f, 0.13f, 0.54f,
        0.36f, 0.58f, 0.34f, 0.42f, 0.33f, 0.30f, 0.32f, 0.19f,
        0.48f, 0.54f, 0.48f, 0.36f, 0.48f, 0.23f, 0.48f, 0.10f,
        0.60f, 0.57f, 0.62f, 0.41f, 0.63f, 0.29f, 0.64f, 0.18f,
        0.72f, 0.63f, 0.76f, 0.50f, 0.78f, 0.41f, 0.80f, 0.32f
    )

    private val connections = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16,
        13 to 17, 17 to 18, 18 to 19, 19 to 20,
        0 to 17
    )

    private val fingertips = setOf(4, 8, 12, 16, 20)

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

    private val revealAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            reveal = it.animatedValue as Float
            invalidate()
        }
    }

    private val pulseAnim = ValueAnimator.ofFloat(0.45f, 1f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            glow = it.animatedValue as Float
            invalidate()
        }
    }

    fun start() {
        revealAnim.start()
        pulseAnim.start()
    }

    override fun onDetachedFromWindow() {
        revealAnim.cancel()
        pulseAnim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val drawn = reveal * connections.size

        glowLinePaint.alpha = (150 * glow).toInt()

        // lines trace themselves one after another
        for ((k, pair) in connections.withIndex()) {
            val frac = (drawn - k).coerceIn(0f, 1f)
            if (frac <= 0f) break
            val sx = pts[pair.first * 2] * w
            val sy = pts[pair.first * 2 + 1] * h
            val ex = sx + (pts[pair.second * 2] * w - sx) * frac
            val ey = sy + (pts[pair.second * 2 + 1] * h - sy) * frac
            canvas.drawLine(sx, sy, ex, ey, glowLinePaint)
            canvas.drawLine(sx, sy, ex, ey, linePaint)
        }

        // joints pop in as the lines reach them
        glowJointPaint.alpha = (170 * glow).toInt()
        for (i in 0 until 21) {
            val scale = (reveal * 21f - i).div(2f).coerceIn(0f, 1f)
            if (scale <= 0f) continue
            val x = pts[i * 2] * w
            val y = pts[i * 2 + 1] * h
            val isTip = i in fingertips
            val radius = (if (isTip) 7f else 4f) * density * scale
            if (isTip) canvas.drawCircle(x, y, radius + 7f * density * scale, glowJointPaint)
            canvas.drawCircle(x, y, radius, jointPaint)
            canvas.drawCircle(x, y, radius, jointRingPaint)
        }
    }
}