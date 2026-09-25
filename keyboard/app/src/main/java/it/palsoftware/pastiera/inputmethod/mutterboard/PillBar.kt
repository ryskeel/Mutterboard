package it.palsoftware.pastiera.inputmethod.mutterboard

import android.content.Context
import android.util.TypedValue

/**
 * Mutterboard: the bar is a floating pill, the way Gboard draws itself over a
 * hardware keyboard, instead of a slab running to the display's edges.
 *
 * Nightly's answer to the Titan 2 Elite's rounded screen corners was to trace
 * them: calibrated corner paths plus solid fills to cover the seams. It never
 * sat flush, and you could see the app through slivers at the sides and
 * bottom. A pill pulled in from every edge has no seam to line up, so while it
 * is on the Titan corner code is switched off entirely
 * (SettingsManager.getTitan2EliteRoundedCornerInsetsEnabled).
 *
 * The chrome clips to the outline and pads its content by the same insets, so
 * everything outside the pill is transparent and nothing inside is cut off.
 */
object PillBar {
    /** Gap between the pill and the left and right edges of the display. */
    const val SIDE_GAP_DP = 8f

    /**
     * Gap under the pill. The navigation/gesture inset wins when it is taller,
     * so the pill never sits on the gesture bar.
     */
    const val BOTTOM_GAP_DP = 8f

    /**
     * Corner radius cap. A single row comes out a true pill (radius = half its
     * height); two rows stacked come out a rounded rectangle, because a radius
     * of half the whole bar would cut into the buttons at its corners.
     */
    const val MAX_RADIUS_DP = 26f

    private const val PREFS = "mutterboard_keyboard"
    private const val KEY_ENABLED = "pill_bar_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
}
