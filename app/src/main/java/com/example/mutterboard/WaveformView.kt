package com.example.mutterboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

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

    private val frontPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = primaryColor
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = primaryColor
        alpha = 80
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val frontPath = Path()
    private val backPath = Path()

    private var smoothedLevel: Float = 0f
    private var targetLevel: Float = 0f
    private var phase: Float = 0f

    fun setLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
    }

    private val ticker = object : Runnable {
        override fun run() {
            smoothedLevel += (targetLevel - smoothedLevel) * 0.25f
            phase += 0.18f
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
        val baseline = 0.08f
        val amp = (h / 2f - dp(4f)) * (baseline + smoothedLevel * 0.92f)
        val freqFront = (2f * PI.toFloat()) * 1.4f / w
        val freqBack = (2f * PI.toFloat()) * 2.1f / w
        val step = dp(3f).coerceAtLeast(1f)

        frontPath.rewind()
        backPath.rewind()

        var x = 0f
        var first = true
        while (x <= w) {
            val yFront = cy + amp * sin(freqFront * x + phase)
            val yBack = cy + (amp * 0.7f) * sin(freqBack * x - phase * 1.3f + 0.7f)
            if (first) {
                frontPath.moveTo(x, yFront)
                backPath.moveTo(x, yBack)
                first = false
            } else {
                frontPath.lineTo(x, yFront)
                backPath.lineTo(x, yBack)
            }
            x += step
        }

        canvas.drawPath(backPath, backPaint)
        canvas.drawPath(frontPath, frontPaint)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        private const val FRAME_MS = 33L
    }
}
