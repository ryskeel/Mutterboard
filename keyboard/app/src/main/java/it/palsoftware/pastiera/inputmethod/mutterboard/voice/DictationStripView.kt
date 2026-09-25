package it.palsoftware.pastiera.inputmethod.mutterboard.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
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
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * What the suggestion row turns into while Mutterboard is dictating: the wave
 * where the suggestions were, and a cancel button. Stop is the mic button
 * itself, which turns into one for the duration.
 *
 * A caption replaces the wave only when something has gone wrong ("Set Groq
 * API key"). Transcribing is said by the wave, as in the overlay.
 */
class DictationStripView(context: Context, heightPx: Int) : LinearLayout(context) {

    enum class Posture { LISTENING, THINKING, DONE, MISSED }

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
        // Inset from the cancel button's side too, so the wave is centred on
        // the bar rather than on what is left of it.
        addView(View(context), LayoutParams(heightPx, heightPx))
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

    fun setLevel(level: Float) {
        wave.level = level.coerceIn(0f, 1f)
    }

    fun setPosture(posture: Posture) {
        wave.posture = posture
    }

    fun setCaption(text: String?) {
        caption.text = text
        caption.visibility = if (text == null) View.GONE else View.VISIBLE
        wave.visibility = if (text == null) View.VISIBLE else View.INVISIBLE
    }

    /**
     * The overlay's squiggle (DictationWave in the app), ported to a View: one
     * long wave that tapers to nothing at both ends and doubles as the level
     * meter. It travels and rides the mic while listening, swells slowly and
     * evenly while thinking, and flattens to a level line when done.
     */
    private class WaveView(context: Context) : View(context) {
        var posture = Posture.LISTENING
        var level = 0f

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics)
        }
        private val path = Path()
        private var phase = 0f
        private var peak = 0f
        private var amp = 0f
        private var last = 0L

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            val dt = if (last == 0L) 0f else ((now - last) / 1000f).coerceIn(0f, 0.1f)
            last = now

            val ampTau = if (level > amp) 0.12f else 0.26f
            amp += (level - amp) * (1f - exp(-dt / ampTau))
            phase += dt * when (posture) {
                Posture.LISTENING -> 2.1f + amp * 1.5f
                Posture.THINKING -> 1.15f
                else -> 0.55f
            }
            val target = when (posture) {
                Posture.LISTENING -> 0.09f + amp * 0.80f
                Posture.THINKING -> 0.24f
                Posture.DONE -> 0f
                Posture.MISSED -> 0.05f
            }
            val tau = if (target > peak) 0.10f else 0.22f
            peak += (target - peak) * (1f - exp(-dt / tau))

            val w = width.toFloat()
            val h = height.toFloat()
            val centreY = h / 2f
            val reach = peak * (h - paint.strokeWidth) / 2f
            path.reset()
            for (i in 0..SAMPLES) {
                val f = i / SAMPLES.toFloat()
                val envelope = sin(PI * f).pow(0.75).toFloat()
                val y = centreY + reach * envelope * sin(f * CYCLES * 2f * PI.toFloat() - phase)
                if (i == 0) path.moveTo(f * w, y) else path.lineTo(f * w, y)
            }
            canvas.drawPath(path, paint)
            if (isShown) postInvalidateOnAnimation()
        }

        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            if (isShown) {
                last = 0L
                postInvalidateOnAnimation()
            }
        }

        private companion object {
            const val SAMPLES = 160
            const val CYCLES = 2.5f
        }
    }
}
