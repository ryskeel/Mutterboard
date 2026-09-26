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
 * The chrome clips its content to the pill and pads it by the same insets, so
 * nothing inside is cut off. Outside the pill it paints a strip in the theme's
 * key grey: left see-through, the gaps showed whatever the app had behind the
 * keyboard, which in Messages is a grey slab of its own window.
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

    /** Gap above the pill, so it sits on the backdrop strip rather than at its edge. */
    const val TOP_GAP_DP = 6f

    /** Space between the row and the pill's rim, top and bottom. */
    const val INNER_PAD_DP = 4f

    /**
     * Corner radius once the bar grows past one row (symbols, emoji,
     * clipboard). Half the height made those pages an oval that cut off their
     * corner keys; they get a card's corners instead.
     */
    const val TALL_RADIUS_DP = 16f

    /** Radius for an outline of this height: a pill for one row, a card for more. */
    fun radiusFor(context: Context, heightPx: Int): Float {
        val oneRow = dp(context, ROW_HEIGHT_DP + INNER_PAD_DP * 2)
        return if (heightPx <= oneRow * 1.25f) heightPx / 2f else dp(context, TALL_RADIUS_DP).toFloat()
    }

    private const val PREFS = "mutterboard_keyboard"
    private const val KEY_ENABLED = "pill_bar_enabled"

    private const val KEY_LAYOUT_APPLIED = "one_row_layout_applied_v1"
    private const val KEY_INDICATORS_APPLIED = "one_row_indicators_applied_v3"

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
     * Shift/Alt/Ctrl/Sym light Pastiera's LED strip along the bottom edge, as
     * they always did before the pill. v2 moved them into the menu row to stop
     * the pill's round ends cutting a full-width strip into stubs; nobody could
     * see what was armed any more. The strip is back, inset to the flat run of
     * the bottom edge ([LED_SIDE_INSET_DP]), and v3 undoes v2 once.
     */
    fun applyIndicatorDefaultOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INDICATORS_APPLIED, false)) return
        SettingsManager.setModifierIndicators(context, setOf(SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP))
        prefs.edit().putBoolean(KEY_INDICATORS_APPLIED, true).apply()
    }

    /**
     * How far the LED strip stays in from the content edge on each side. The
     * strip sits a few dp above the rim, where the one-row pill's end curves
     * are still about 15dp deep from its side; closer in, the round ends clip
     * the outer LEDs.
     */
    const val LED_SIDE_INSET_DP = 14f

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
}
