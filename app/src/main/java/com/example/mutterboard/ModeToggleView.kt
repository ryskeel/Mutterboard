package com.example.mutterboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.max

/**
 * Two-position segmented toggle for the refine mode, drawn as a pill with a
 * sliding thumb.
 *
 * A single button naming one mode is ambiguous — it reads equally as the state
 * you're in and as the state you'd get by tapping — and on a keyboard, where the
 * control is glanced at rather than studied, that ambiguity costs a dictation.
 * Showing both labels at once removes the question: the filled half is where you
 * are, the empty half is where a tap takes you.
 *
 * The thumb animates between the halves rather than cutting, which is what makes
 * the two labels read as one control with a position instead of two separate
 * buttons.
 *
 * Drawn in a single [View] rather than composed from child views, matching
 * [WaveformView]: the whole control is a rounded rect, a moving rounded rect and
 * two strings, and Canvas expresses the interpolated label colours far more
 * directly than a ViewGroup would. Colours come from theme attributes, so it
 * follows Material You dynamic colour like the rest of the keyboard.
 */
class ModeToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // The thumb is colorPrimary — the same fill as the Stop button — rather than
    // a container role. Container colours are only a step or two off the track in
    // tone, and under a neutral Material You palette (a monochrome wallpaper, say)
    // they collapse into it entirely and the selected half becomes invisible.
    // Primary is the one role guaranteed to contrast with the surface in every
    // generated scheme, which is exactly the guarantee a selection indicator needs.
    private val trackColor = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest)
    private val thumbColor = themeColor(com.google.android.material.R.attr.colorPrimary)
    private val selectedTextColor = themeColor(com.google.android.material.R.attr.colorOnPrimary)
    private val unselectedTextColor = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        textSize = sp(13f)
    }
    private val rect = RectF()

    /**
     * Thumb position as a continuous 0..1 (left label to right label) rather
     * than a boolean, so the label colours can cross-fade in step with the slide
     * instead of snapping at the halfway point.
     */
    private var thumbPos = 0f
    private var animator: ValueAnimator? = null

    /** Which half is selected. Setting this directly moves the thumb without animating. */
    var isCasual: Boolean = false
        private set

    /** Fired only on a user tap, never on a programmatic [setMode]. */
    var onModeChanged: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
    }

    /**
     * Move to [casual]. [animate] is false for the initial paint, where a thumb
     * sliding in from the wrong side would suggest the mode had just changed.
     */
    fun setMode(casual: Boolean, animate: Boolean) {
        isCasual = casual
        val target = if (casual) 1f else 0f
        animator?.cancel()
        if (!animate) {
            thumbPos = target
            invalidate()
            updateAccessibility()
            return
        }
        animator = ValueAnimator.ofFloat(thumbPos, target).apply {
            duration = SLIDE_MS
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                thumbPos = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        updateAccessibility()
    }

    override fun performClick(): Boolean {
        super.performClick()
        setMode(!isCasual, animate = true)
        onModeChanged?.invoke(isCasual)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Default View click handling is enough — the thumb tracks the selection,
        // not the finger, so there's no drag to interpret. Tapping the half you're
        // already on still flips, matching how the single button behaved.
        return super.onTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widest = max(textPaint.measureText(LABEL_DEFAULT), textPaint.measureText(LABEL_CASUAL))
        val desiredWidth = ((widest + dp(20f)) * 2).toInt() + paddingLeft + paddingRight
        val desiredHeight = dp(34f).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f

        fillPaint.color = trackColor
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        val inset = dp(2f)
        val halfWidth = w / 2f
        val thumbLeft = inset + thumbPos * (halfWidth - inset)
        fillPaint.color = thumbColor
        rect.set(thumbLeft, inset, thumbLeft + halfWidth - inset, h - inset)
        canvas.drawRoundRect(rect, radius - inset, radius - inset, fillPaint)

        // Vertically centre on the text's own metrics rather than on h/2, so the
        // labels sit optically centred rather than baseline-centred.
        val baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        textPaint.color = blend(selectedTextColor, unselectedTextColor, thumbPos)
        canvas.drawText(LABEL_DEFAULT, halfWidth / 2f, baseline, textPaint)
        textPaint.color = blend(unselectedTextColor, selectedTextColor, thumbPos)
        canvas.drawText(LABEL_CASUAL, halfWidth + halfWidth / 2f, baseline, textPaint)
    }

    private fun updateAccessibility() {
        val current = if (isCasual) LABEL_CASUAL else LABEL_DEFAULT
        val other = if (isCasual) LABEL_DEFAULT else LABEL_CASUAL
        contentDescription = "Refine mode: $current. Tap to switch to $other."
    }

    /** Linear blend in sRGB; the two endpoints are close enough in tone that a
     *  perceptual space would not visibly differ over a 200ms slide. */
    private fun blend(from: Int, to: Int, t: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
    )

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    /** Text scales with the user's font size setting; the pill's geometry does not. */
    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        private const val LABEL_DEFAULT = "Default"
        private const val LABEL_CASUAL = "Casual"
        private const val SLIDE_MS = 200L
    }
}
