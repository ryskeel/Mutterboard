package com.example.mutterboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Listening indicator: a centered row of vertical bars that grow outward from
 * the middle as the user speaks, echoing the app icon's coral waveform mark.
 *
 * Two things carry the icon's identity: the silhouette is tallest in the centre
 * and tapers toward the edges, and each bar's opacity steps down from the centre
 * outward to give a sense of depth. The bars are tinted with the theme's
 * colorPrimary, so the indicator adapts to the phone's theming (including
 * Material You dynamic color) rather than being hard-coded coral. Per-bar wiggle
 * keeps it feeling like a live equalizer instead of one rigid shape.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val primaryColor: Int = run {
        val tv = TypedValue()
        context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
        tv.data
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = primaryColor
    }
    private val rect = RectF()

    private var smoothedLevel: Float = 0f
    private var targetLevel: Float = 0f
    private var phase: Float = 0f

    fun setLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
    }

    private val ticker = object : Runnable {
        override fun run() {
            smoothedLevel += (targetLevel - smoothedLevel) * 0.25f
            phase += 0.28f
            invalidate()
            postDelayed(this, FRAME_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cy = h / 2f
        val barWidth = dp(7f)
        val gap = dp(8f)
        val pitch = barWidth + gap
        val groupWidth = (BAR_COUNT - 1) * pitch + barWidth
        val startX = (w - groupWidth) / 2f
        val center = (BAR_COUNT - 1) / 2f
        val maxHalf = (h / 2f - dp(3f)).coerceAtLeast(barWidth)
        val radius = barWidth / 2f

        // Volume maps onto a baseline so silence still shows a gentle shimmer
        // (clearly "listening") and speech drives the bars toward full height.
        val level = IDLE_BASELINE + (1f - IDLE_BASELINE) * smoothedLevel

        for (i in 0 until BAR_COUNT) {
            val distFromCenter = abs(i - center) / center  // 0 at centre, 1 at edges

            // Icon-like silhouette: tallest in the middle, tapering to the edges.
            val envelope = 0.34f + 0.66f * cos(distFromCenter * (PI.toFloat() / 2f))

            // Per-bar wiggle so bars bounce at slightly different rates. The
            // wiggle depth scales with the current level, so the bars are nearly
            // still when silent and only come alive as the user speaks.
            val wiggle = 0.5f + 0.5f * sin(phase + i * 0.7f)
            val motion = IDLE_MOTION + (WIGGLE_DEPTH - IDLE_MOTION) * smoothedLevel
            val liveness = (1f - motion) + motion * wiggle

            val half = (maxHalf * envelope * level * liveness).coerceAtLeast(radius)

            val left = startX + i * pitch
            rect.set(left, cy - half, left + barWidth, cy + half)

            // Depth via opacity: brightest in the centre, fading toward the edges.
            barPaint.color = primaryColor
            barPaint.alpha =
                (EDGE_ALPHA + (CENTER_ALPHA - EDGE_ALPHA) * (1f - distFromCenter)).toInt()
            canvas.drawRoundRect(rect, radius, radius, barPaint)
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        private const val FRAME_MS = 33L
        private const val BAR_COUNT = 11
        private const val IDLE_BASELINE = 0.14f
        // Maximum per-bar wiggle (reached at full volume) and the tiny residual
        // wiggle at silence — small enough to read as "calmly listening".
        private const val WIGGLE_DEPTH = 0.5f
        private const val IDLE_MOTION = 0.05f
        private const val CENTER_ALPHA = 255f
        private const val EDGE_ALPHA = 70f
    }
}
