package com.project_by_adr.airgestures

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class HoldRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private var progress = 0f
    private val rect = RectF()

    private val trackPaint = Paint().apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        isAntiAlias = true
    }

    private val arcPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    /** 0 = hidden, 1 = full ring */
    fun setHold(value: Float) {
        val p = value.coerceIn(0f, 1f)
        if (p == progress) return
        progress = p
        visibility = if (p > 0.02f) VISIBLE else INVISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 6f * density
        rect.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)
        canvas.drawArc(rect, -90f, 360f * progress, false, arcPaint)
    }
}