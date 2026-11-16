package it.palsoftware.pastiera.inputmethod.mutterboard.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * The Default/Casual refine toggle on the physical keyboard's dictation strip:
 * the overlay's ModePill, drawn in the strip's ink.
 *
 * Both labels are always shown, for the same reason as everywhere else in the
 * app: a single button naming one mode reads equally as where you are and as
 * where a tap takes you. Tapping a side selects it, as on the overlay.
 *
 * Quiet on purpose. It is a setting changed once in a while, not part of
 * dictating, so the selected side is a lift rather than a filled accent. Ink,
 * not theme colours, because the strip sits on the mist, whose tone comes from
 * the bar underneath rather than from the theme.
 */
class ModePillView(context: Context) : View(context) {

    var casual = false
        private set

    /** Fired only on a tap that changes the mode. */
    var onModeChanged: ((Boolean) -> Unit)? = null

    var ink = Color.WHITE
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        updateDescription()
    }

    fun setMode(casual: Boolean) {
        if (this.casual == casual) return
        this.casual = casual
        updateDescription()
        invalidate()
    }

    // A touch picks the side it lands on.
    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val inside = event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()
                if (inside) pick(event.x >= width / 2f)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // Accessibility services click without coordinates, so a click flips.
    override fun performClick(): Boolean {
        super.performClick()
        pick(!casual)
        return true
    }

    private fun pick(casual: Boolean) {
        if (casual == this.casual) return
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        setMode(casual)
        onModeChanged?.invoke(casual)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widest = max(text.measureText(DEFAULT), text.measureText(CASUAL))
        val w = ((widest + dp(20f)) * 2 + dp(4f)).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(dp(28f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val pillH = minOf(height.toFloat(), dp(28f))
        val top = (height - pillH) / 2f
        val r = pillH / 2f
        fill.color = withAlpha(ink, 0.16f)
        rect.set(0f, top, width.toFloat(), top + pillH)
        canvas.drawRoundRect(rect, r, r, fill)

        val inset = dp(2f)
        val half = width / 2f
        val left = if (casual) half else inset
        fill.color = withAlpha(ink, 0.26f)
        rect.set(left, top + inset, left + half - inset, top + pillH - inset)
        canvas.drawRoundRect(rect, r - inset, r - inset, fill)

        val baseline = top + pillH / 2f - (text.descent() + text.ascent()) / 2f
        drawLabel(canvas, DEFAULT, half / 2f, baseline, selected = !casual)
        drawLabel(canvas, CASUAL, half + half / 2f, baseline, selected = casual)
    }

    private fun drawLabel(canvas: Canvas, label: String, x: Float, y: Float, selected: Boolean) {
        text.typeface = if (selected) medium else regular
        text.color = if (selected) ink else withAlpha(ink, 0.65f)
        canvas.drawText(label, x, y, text)
    }

    private fun updateDescription() {
        val (now, other) = if (casual) CASUAL to DEFAULT else DEFAULT to CASUAL
        contentDescription = "Refine mode: $now. Tap to switch to $other."
    }

    private fun withAlpha(color: Int, a: Float) =
        Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private companion object {
        const val DEFAULT = "Default"
        const val CASUAL = "Casual"
    }
}
