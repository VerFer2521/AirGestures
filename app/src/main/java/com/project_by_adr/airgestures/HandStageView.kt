package com.project_by_adr.airgestures

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen "hand only" stage: a calm animated background and a glowing
 * hand skeleton, instead of your face on the camera preview.
 *
 * Drawn: drifting aurora lights, floating dust, ripples while waiting for a
 * hand, a glowing skeleton with a different colour per finger, fingertip
 * trails, a hold ring in the palm and a pulse when a gesture fires.
 */
class HandStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ----------------------------------------------------------------- state
    private val targetX = FloatArray(21)
    private val targetY = FloatArray(21)
    private val shownX = FloatArray(21)
    private val shownY = FloatArray(21)
    private var haveHand = false
    private var handMix = 0f           // 0 = no hand, 1 = fully shown
    private var holdTarget = 0f
    private var holdShown = 0f
    private var cameraBehind = false   // true: camera preview is visible underneath
    private var pulseAt = -10000f

    private var timeMs = 0f
    private var lastNs = 0L
    private var running = false

    // fingertip trails
    private val tips = intArrayOf(4, 8, 12, 16, 20)
    private val trailLen = 14
    private val trailX = Array(5) { FloatArray(trailLen) }
    private val trailY = Array(5) { FloatArray(trailLen) }
    private var trailHead = 0
    private var trailCount = 0

    // floating dust
    private val dustCount = 38
    private val dustX = FloatArray(dustCount) { Random.nextFloat() }
    private val dustY = FloatArray(dustCount) { Random.nextFloat() }
    private val dustSpeed = FloatArray(dustCount) { 0.01f + Random.nextFloat() * 0.03f }
    private val dustSize = FloatArray(dustCount) { 1f + Random.nextFloat() * 1.8f }
    private val dustPhase = FloatArray(dustCount) { Random.nextFloat() * 6.28f }

    // ---------------------------------------------------------------- colours
    private val fingerColors = intArrayOf(
        Color.parseColor("#FF9A3D"),   // thumb
        Color.parseColor("#00E5FF"),   // index
        Color.parseColor("#4D8DFF"),   // middle
        Color.parseColor("#A66BFF"),   // ring
        Color.parseColor("#FF2E92")    // pinky
    )
    private val palmColor = Color.parseColor("#9FE9FF")
    private val green = Color.parseColor("#4CFF9A")

    private val bones = arrayOf(
        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),
        intArrayOf(0, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),
        intArrayOf(5, 9), intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
        intArrayOf(9, 13), intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),
        intArrayOf(13, 17), intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),
        intArrayOf(0, 17)
    )
    private val palmOutline = intArrayOf(0, 1, 5, 9, 13, 17)

    // ----------------------------------------------------------------- paints
    private val bgPaint = Paint()
    private val auroraCyan = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auroraMagenta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auroraViolet = Paint(Paint.ANTI_ALIAS_FLAG)
    private var auroraRadius = 1f

    private val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#00E5FF")
    }
    private val idleEmojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(46f)
        textAlign = Paint.Align.CENTER
    }
    private val idleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(15f)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
        color = Color.WHITE
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.05f
        color = Color.parseColor("#8A94A6")
    }

    private val palmFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(11f)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(3.4f)
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val ringTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(5f)
    }
    private val ringGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(12f)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(5f)
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val palmPath = Path()
    private val oval = RectF()

    // -------------------------------------------------------------- public API

    fun updateLandmarks(landmarks: List<NormalizedLandmark>) {
        if (landmarks.size < 21) return
        for (i in 0 until 21) {
            targetX[i] = landmarks[i].x()
            targetY[i] = landmarks[i].y()
        }
        if (!haveHand && handMix < 0.05f) {
            // a hand just appeared: start in place instead of flying in
            for (i in 0 until 21) {
                shownX[i] = targetX[i]
                shownY[i] = targetY[i]
            }
            trailCount = 0
        }
        haveHand = true
    }

    fun clear() {
        haveHand = false
        holdTarget = 0f
    }

    /** 0..1: how far the hold-to-confirm ring is filled. */
    fun setHold(progress: Float) {
        holdTarget = progress.coerceIn(0f, 1f)
    }

    /** A small burst of rings from the palm, for when an action fires. */
    fun pulse() {
        pulseAt = timeMs
    }

    /** true when the camera preview is visible underneath (then no own background). */
    fun setCameraBehind(value: Boolean) {
        cameraBehind = value
        invalidate()
    }

    // --------------------------------------------------------------- lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    private fun start() {
        if (running) return
        running = true
        lastNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.parseColor("#060A14"), Color.parseColor("#0D1430"), Shader.TileMode.CLAMP
        )
        auroraRadius = max(w, h) * 0.55f
        auroraCyan.shader = RadialGradient(
            0f, 0f, auroraRadius,
            Color.argb(70, 0, 229, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        auroraMagenta.shader = RadialGradient(
            0f, 0f, auroraRadius,
            Color.argb(60, 255, 46, 146), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        auroraViolet.shader = RadialGradient(
            0f, 0f, auroraRadius,
            Color.argb(65, 130, 90, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
    }

    // ------------------------------------------------------------------ frames

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastNs == 0L) 16f else ((frameTimeNanos - lastNs) / 1_000_000f).coerceIn(1f, 50f)
        lastNs = frameTimeNanos
        timeMs += dt

        // glide the shown skeleton toward the newest AI result
        val k = 1f - exp(-dt / 45f)
        for (i in 0 until 21) {
            shownX[i] += (targetX[i] - shownX[i]) * k
            shownY[i] += (targetY[i] - shownY[i]) * k
        }

        // fade the hand in and out
        val goal = if (haveHand) 1f else 0f
        val step = dt / (if (haveHand) 220f else 340f)
        handMix = if (handMix < goal) min(goal, handMix + step) else max(goal, handMix - step)

        holdShown += (holdTarget - holdShown) * (1f - exp(-dt / 60f))

        if (handMix > 0.05f) pushTrail()

        // dust drifts upward
        for (i in 0 until dustCount) {
            dustY[i] -= dustSpeed[i] * dt / 1000f
            if (dustY[i] < -0.02f) {
                dustY[i] = 1.02f
                dustX[i] = Random.nextFloat()
            }
        }

        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun pushTrail() {
        for (f in 0 until 5) {
            trailX[f][trailHead] = shownX[tips[f]]
            trailY[f][trailHead] = shownY[tips[f]]
        }
        trailHead = (trailHead + 1) % trailLen
        trailCount = min(trailCount + 1, trailLen)
    }

    // ------------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (!cameraBehind) {
            canvas.drawRect(0f, 0f, w, h, bgPaint)
            drawAurora(canvas, w, h)
            drawDust(canvas, w, h)
        }

        if (handMix < 0.98f) drawWaiting(canvas, w, h)
        if (handMix > 0.01f) drawHand(canvas, w, h)
    }

    private fun drawAurora(canvas: Canvas, w: Float, h: Float) {
        // the lights lean slightly toward the hand
        val lean = if (handMix > 0.01f) (shownX[9] - 0.5f) * -w * 0.10f * handMix else 0f
        val t = timeMs / 1000f

        canvas.save()
        canvas.translate(w * (0.20f + 0.10f * sin(t * 0.35f)) + lean, h * (0.25f + 0.06f * sin(t * 0.5f)))
        canvas.drawCircle(0f, 0f, auroraRadius, auroraCyan)
        canvas.restore()

        canvas.save()
        canvas.translate(w * (0.85f + 0.08f * sin(t * 0.4f + 1f)) + lean, h * (0.55f + 0.08f * sin(t * 0.3f)))
        canvas.drawCircle(0f, 0f, auroraRadius, auroraMagenta)
        canvas.restore()

        canvas.save()
        canvas.translate(w * (0.45f + 0.12f * sin(t * 0.25f + 2f)) + lean, h * (0.90f + 0.05f * sin(t * 0.45f)))
        canvas.drawCircle(0f, 0f, auroraRadius, auroraViolet)
        canvas.restore()
    }

    private fun drawDust(canvas: Canvas, w: Float, h: Float) {
        for (i in 0 until dustCount) {
            val twinkle = 0.5f + 0.5f * sin(timeMs / 700f + dustPhase[i])
            dustPaint.alpha = (30 + 90 * twinkle).toInt()
            canvas.drawCircle(dustX[i] * w, dustY[i] * h, dp(dustSize[i]), dustPaint)
        }
    }

    /** Ripples + a waving hand while no hand is visible. */
    private fun drawWaiting(canvas: Canvas, w: Float, h: Float) {
        val fade = 1f - handMix
        val cx = w / 2f
        val cy = h * 0.46f

        for (i in 0 until 3) {
            val phase = ((timeMs / 2600f) + i / 3f) % 1f
            val radius = dp(34f) + phase * dp(120f)
            ripplePaint.alpha = ((1f - phase) * 90f * fade).toInt()
            canvas.drawCircle(cx, cy, radius, ripplePaint)
        }

        val bob = sin(timeMs / 500f) * dp(5f)
        val wave = sin(timeMs / 380f) * 9f
        idleEmojiPaint.alpha = (255 * fade).toInt()
        canvas.save()
        canvas.rotate(wave, cx, cy + dp(16f))
        canvas.drawText("✋", cx, cy + dp(16f) + bob, idleEmojiPaint)
        canvas.restore()

        val breathe = 0.6f + 0.4f * sin(timeMs / 650f)
        idleTextPaint.alpha = (230 * breathe * fade).toInt()
        canvas.drawText("Show your hand", cx, cy + dp(92f), idleTextPaint)

        hintPaint.alpha = (200 * fade).toInt()
        canvas.drawText("tap anywhere to show or hide the camera", cx, h - dp(130f), hintPaint)
    }

    private fun drawHand(canvas: Canvas, w: Float, h: Float) {
        // the hand always keeps its shape: fit a 3:4 box in the middle
        val boxH = min(h * 0.78f, w * 0.98f / 0.75f)
        val boxW = boxH * 0.75f
        val left = (w - boxW) / 2f
        val top = (h - boxH) / 2f

        fun px(i: Int) = left + (1f - shownX[i]) * boxW     // mirrored like a selfie
        fun py(i: Int) = top + shownY[i] * boxH

        val cx = (px(0) + px(5) + px(9) + px(13) + px(17)) / 5f
        val cy = (py(0) + py(5) + py(9) + py(13) + py(17)) / 5f
        val handSize = hypot(px(9) - px(0), py(9) - py(0))

        val mix = handMix
        val ease = mix * mix * (3f - 2f * mix)
        val scale = 0.93f + 0.07f * ease

        // flash right after an action fired
        val pulseAge = (timeMs - pulseAt) / 750f
        val flash = if (pulseAge in 0f..0.4f) (1f - pulseAge / 0.4f) else 0f

        canvas.save()
        canvas.scale(scale, scale, cx, cy)

        // soft palm
        palmPath.reset()
        for ((n, idx) in palmOutline.withIndex()) {
            if (n == 0) palmPath.moveTo(px(idx), py(idx)) else palmPath.lineTo(px(idx), py(idx))
        }
        palmPath.close()
        palmFill.color = ColorUtils.setAlphaComponent(palmColor, (34 * mix).toInt())
        canvas.drawPath(palmPath, palmFill)

        drawTrails(canvas, boxW, boxH, left, top, mix)

        // bones: glow underneath, bright line on top
        for (bone in bones) {
            val a = bone[0]
            val b = bone[1]
            val finger = if (b >= 1 && !(a == 0 && (b == 5 || b == 17)) && !(a == 5 && b == 9) &&
                !(a == 9 && b == 13) && !(a == 13 && b == 17)
            ) (b - 1) / 4 else -1
            var color = if (finger >= 0) fingerColors[finger] else palmColor
            if (flash > 0f) color = ColorUtils.blendARGB(color, Color.WHITE, flash * 0.75f)

            glowLine.color = color
            glowLine.alpha = ((70 + 60 * flash) * mix).toInt().coerceAtMost(255)
            canvas.drawLine(px(a), py(a), px(b), py(b), glowLine)

            line.color = color
            line.alpha = (255 * mix).toInt()
            canvas.drawLine(px(a), py(a), px(b), py(b), line)
        }

        // joints and glowing fingertips
        val breathe = 0.5f + 0.5f * sin(timeMs / 420f)
        for (i in 0 until 21) {
            val isTip = i == 4 || i == 8 || i == 12 || i == 16 || i == 20
            val finger = if (i == 0) -1 else (i - 1) / 4
            val color = if (finger >= 0) fingerColors[finger] else palmColor
            if (isTip) {
                halo.color = color
                halo.alpha = ((55 + 40 * breathe) * mix).toInt()
                canvas.drawCircle(px(i), py(i), dp(14f + 3f * breathe), halo)
                dot.color = color
                dot.alpha = (255 * mix).toInt()
                canvas.drawCircle(px(i), py(i), dp(6.5f), dot)
                core.alpha = (255 * mix).toInt()
                canvas.drawCircle(px(i), py(i), dp(2.6f), core)
            } else {
                dot.color = color
                dot.alpha = (230 * mix).toInt()
                canvas.drawCircle(px(i), py(i), dp(3.8f), dot)
            }
        }

        drawHoldRing(canvas, cx, cy, handSize, mix)
        drawPulse(canvas, cx, cy, handSize, pulseAge)

        canvas.restore()
    }

    private fun drawTrails(canvas: Canvas, boxW: Float, boxH: Float, left: Float, top: Float, mix: Float) {
        if (trailCount < 2) return
        for (f in 0 until 5) {
            trailPaint.color = fingerColors[f]
            for (j in 1 until trailCount) {
                // j = 0 is the oldest point, trailCount - 1 the newest
                val older = (trailHead - trailCount + j - 1 + trailLen * 2) % trailLen
                val newer = (trailHead - trailCount + j + trailLen * 2) % trailLen
                val t = j / trailCount.toFloat()
                trailPaint.alpha = (150 * t * mix).toInt()
                trailPaint.strokeWidth = dp(1.5f + 3.5f * t)
                canvas.drawLine(
                    left + (1f - trailX[f][older]) * boxW, top + trailY[f][older] * boxH,
                    left + (1f - trailX[f][newer]) * boxW, top + trailY[f][newer] * boxH,
                    trailPaint
                )
            }
        }
    }

    private fun drawHoldRing(canvas: Canvas, cx: Float, cy: Float, handSize: Float, mix: Float) {
        if (holdShown < 0.02f) return
        val radius = handSize * 0.95f
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val color = ColorUtils.blendARGB(Color.parseColor("#00E5FF"), green, holdShown)

        ringTrack.color = Color.WHITE
        ringTrack.alpha = (36 * mix).toInt()
        canvas.drawArc(oval, 0f, 360f, false, ringTrack)

        ringGlow.color = color
        ringGlow.alpha = (70 * mix).toInt()
        canvas.drawArc(oval, -90f, 360f * holdShown, false, ringGlow)

        ring.color = color
        ring.alpha = (255 * mix).toInt()
        canvas.drawArc(oval, -90f, 360f * holdShown, false, ring)
    }

    private fun drawPulse(canvas: Canvas, cx: Float, cy: Float, handSize: Float, age: Float) {
        if (age < 0f || age > 1f) return
        for (i in 0 until 2) {
            val a = age - i * 0.14f
            if (a < 0f || a > 1f) continue
            pulsePaint.color = ColorUtils.blendARGB(green, Color.parseColor("#00E5FF"), a)
            pulsePaint.alpha = ((1f - a) * 210f).toInt()
            pulsePaint.strokeWidth = dp(4f - 3f * a)
            canvas.drawCircle(cx, cy, handSize * (0.7f + 1.9f * a), pulsePaint)
        }
    }
}