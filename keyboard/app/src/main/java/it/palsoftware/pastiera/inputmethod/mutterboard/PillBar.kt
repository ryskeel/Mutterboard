package it.palsoftware.pastiera.inputmethod.mutterboard

import android.content.Context
import android.util.TypedValue
import it.palsoftware.pastiera.SettingsManager

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
    const val SIDE_GAP_DP = 16f

    /**
     * Gap under the pill. The navigation/gesture inset wins when it is taller,
     * so the pill never sits on the gesture bar.
     */
    const val BOTTOM_GAP_DP = 8f

    /**
     * The row inside the pill. Pastiera's 36dp was sized for a strip under a
     * second row; alone, it left the buttons cramped against the rim.
     */
    const val ROW_HEIGHT_DP = 44f

    /** Space between the row and the pill's rim, top and bottom. */
    const val INNER_PAD_DP = 4f

    private const val PREFS = "mutterboard_keyboard"
    private const val KEY_ENABLED = "pill_bar_enabled"

    private const val KEY_LAYOUT_APPLIED = "one_row_layout_applied_v1"
    private const val KEY_INDICATORS_APPLIED = "one_row_indicators_applied_v1"

    /**
     * The pill is one row, like Gboard's: menu on the left, suggestions in the
     * middle, mic on the right. That is nightly's Pastierina presentation with
     * its buttons chosen, so it is set once rather than enforced: anyone who
     * later picks the two-row bar in settings keeps it.
     */
    fun applyOneRowLayoutOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LAYOUT_APPLIED, false)) return
        SettingsManager.setStatusBarPresentationMode(context, SettingsManager.StatusBarPresentationMode.PASTIERINA)
        SettingsManager.setPastierinaStatusBarSlotsLeft(context, listOf(SettingsManager.STATUS_BAR_BUTTON_HAMBURGER))
        SettingsManager.setPastierinaStatusBarSlotsRight(context, listOf(SettingsManager.STATUS_BAR_BUTTON_MICROPHONE))
        prefs.edit().putBoolean(KEY_LAYOUT_APPLIED, true).apply()
    }

    /**
     * Shift/Alt/Ctrl show inside the row instead of on Pastiera's LED strip
     * along the bottom edge: the strip is a full-width line, and the pill's
     * rounded ends cut it into stubs.
     */
    fun applyIndicatorDefaultOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INDICATORS_APPLIED, false)) return
        SettingsManager.setModifierIndicators(context, setOf(SettingsManager.MODIFIER_INDICATOR_STATUS_BAR))
        prefs.edit().putBoolean(KEY_INDICATORS_APPLIED, true).apply()
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
}
