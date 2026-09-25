package it.palsoftware.pastiera.inputmethod.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import it.palsoftware.pastiera.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the suggestion row turns into while Mutterboard is dictating: the wave
 * where the suggestions were, and a cancel button. Stop is the mic button
 * itself, which turns into one for the duration.
 *
 * A caption replaces the wave whenever there is something to say instead
 * ("Transcribing…", "Set Groq API key").
 */
class DictationStripView(context: Context, heightPx: Int) : LinearLayout(context) {

    var onCancel: (() -> Unit)? = null

    private val wave = WaveView(context)
    private val caption = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        visibility = View.GONE
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, heightPx)
        minimumHeight = heightPx
        visibility = View.GONE

        val middle = FrameLayout(context).apply {
            addView(wave, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(caption, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        addView(middle, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        val cancel = ImageView(context).apply {
            setImageResource(R.drawable.ic_close_24)
            setColorFilter(Color.WHITE)
            contentDescription = "Cancel dictation"
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onCancel?.invoke()
            }
        }
        addView(cancel, LayoutParams(heightPx, heightPx))
    }

    fun setLevel(level: Float) = wave.setLevel(level)

    fun setCaption(text: String?) {
        caption.text = text
        caption.visibility = if (text == null) View.GONE else View.VISIBLE
        wave.visibility = if (text == null) View.VISIBLE else View.INVISIBLE
    }

    /**
     * Mutterboard's listening wave (WaveformView in the app), copied rather than
     * shared: the app's copy is tinted from a Material theme this keyboard does
     * not run under, and it draws on a dark bar here, so it is white.
     */
    private class WaveView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val rect = RectF()
        private var smoothed = 0f
        private var target = 0f
        private var phase = 0f

        fun setLevel(level: Float) {
            target = level.coerceIn(0f, 1f)
        }

        private val ticker = object : Runnable {
            override fun run() {
                smoothed += (target - smoothed) * 0.25f
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
            val barWidth = dp(5f)
            val pitch = barWidth + dp(6f)
            val startX = (w - ((BAR_COUNT - 1) * pitch + barWidth)) / 2f
            val center = (BAR_COUNT - 1) / 2f
            val maxHalf = (h / 2f - dp(4f)).coerceAtLeast(barWidth)
            val radius = barWidth / 2f
            val level = IDLE_BASELINE + (1f - IDLE_BASELINE) * smoothed
            for (i in 0 until BAR_COUNT) {
                val dist = abs(i - center) / center
                val envelope = 0.34f + 0.66f * cos(dist * (PI.toFloat() / 2f))
                val wiggle = 0.5f + 0.5f * sin(phase + i * 0.7f)
                val motion = IDLE_MOTION + (WIGGLE_DEPTH - IDLE_MOTION) * smoothed
                val half = (maxHalf * envelope * level * ((1f - motion) + motion * wiggle)).coerceAtLeast(radius)
                val left = startX + i * pitch
                rect.set(left, cy - half, left + barWidth, cy + half)
                paint.alpha = (EDGE_ALPHA + (255f - EDGE_ALPHA) * (1f - dist)).toInt()
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }

        private fun dp(v: Float) =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

        companion object {
            const val FRAME_MS = 33L
            const val BAR_COUNT = 15
            const val IDLE_BASELINE = 0.14f
            const val WIGGLE_DEPTH = 0.5f
            const val IDLE_MOTION = 0.05f
            const val EDGE_ALPHA = 70f
        }
    }
}
