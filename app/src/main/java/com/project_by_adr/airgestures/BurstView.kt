package com.project_by_adr.airgestures

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class BurstView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private var t = 0f
    private var animator: ValueAnimator? = null

    private val ringPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    /** Plays the ripple once. Safe to call again while it is running. */
    fun play() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 850
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    t = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (t <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = width.coerceAtMost(height) * 0.55f

        // two rings, the second one slightly behind the first
        for (k in 0..1) {
            val p = ((t - k * 0.18f) / (1f - k * 0.18f)).coerceIn(0f, 1f)
            if (p <= 0f) continue
            ringPaint.alpha = ((1f - p) * 200).toInt()
            ringPaint.strokeWidth = (4f - 2.5f * p) * density
            canvas.drawCircle(cx, cy, 30f * density + p * maxRadius, ringPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}