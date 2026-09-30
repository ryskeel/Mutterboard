package it.palsoftware.pastiera.inputmethod.mutterboard

import it.palsoftware.pastiera.SettingEntry
import it.palsoftware.pastiera.SettingLinkIds
import it.palsoftware.pastiera.SettingsDestination

/**
 * The keyboard settings Mutterboard does not show: Ry's cuts from Pastiera's
 * screens, which were too heavy for one phone and one person.
 *
 * Hide a row by adding its link id here and wrapping it in
 * `if (!HiddenSettings.hides(id))`. Never delete it: Pastiera's link tests read
 * the source and expect every registered row to still be there, and a wrap is
 * a far smaller diff to carry across an upstream merge. Search reads this same
 * list, so a hidden row can never be a search result that leads nowhere.
 */
object HiddenSettings {
    private val ids = setOf(
        SettingLinkIds.MAIN_KEYBOARDS_DEVICES,
        SettingLinkIds.MODIFIERS_INDICATORS,
        SettingLinkIds.MODIFIERS_INDICATOR_BOTTOM_STRIP,
        SettingLinkIds.MODIFIERS_INDICATOR_MENU_BAR,
        SettingLinkIds.MODIFIERS_INDICATOR_STATUS_BAR,
        SettingLinkIds.MODIFIERS_ALT_KEY_SHORTCUTS,
        STATUS_BAR_STYLE
    )

    // Whole screens: everything routed into one is hidden with its entry row.
    private val destinations = setOf(SettingsDestination.KeyboardsDevices)

    const val STATUS_BAR_STYLE = "status_bar.presentation"

    fun hides(id: String): Boolean = id in ids

    fun hides(entry: SettingEntry): Boolean =
        entry.id in ids || entry.route.destination in destinations
}
