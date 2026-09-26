package it.palsoftware.pastiera.inputmethod.mutterboard

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import it.palsoftware.pastiera.SettingsManager

/**
 * Mutterboard: the "Material You" keyboard theme. Every Pastiera theme is a
 * fixed set of colours, so none of them matched the phone's wallpaper palette
 * or changed with dark mode unless you assigned a light and a dark theme by
 * hand. This one reads the system palette each time the theme is looked up.
 *
 * The roles follow Material 3: the strip behind the pill is the secondary
 * container, the pill is the surface, its chips and keys are a surface
 * container, and text is on-surface. Modifier LEDs use primary for active and
 * tertiary for locked, so the two stay distinguishable in any palette.
 */
object MaterialYouTheme {

    fun resolve(context: Context, theme: SettingsManager.KeyboardThemeSettings): SettingsManager.KeyboardThemeSettings {
        if (!theme.materialYou || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return theme
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        fun c(id: Int) = context.getColor(id)
        return if (dark) {
            theme.copy(
                background = c(android.R.color.system_neutral1_900),
                divider = c(android.R.color.system_neutral2_700),
                normalKey = c(android.R.color.system_neutral1_800),
                specialKey = c(android.R.color.system_accent2_700),
                textAndIcons = c(android.R.color.system_neutral1_100),
                ledInactive = c(android.R.color.system_neutral2_700),
                ledActive = c(android.R.color.system_accent1_200),
                ledLocked = c(android.R.color.system_accent3_200),
                accent = c(android.R.color.system_accent1_200),
                cursorSwipe = c(android.R.color.system_accent1_200),
                keyPopup = c(android.R.color.system_accent2_700),
                keyPopupSelected = c(android.R.color.system_accent1_200),
                suggestion = c(android.R.color.system_neutral1_800),
                statusBarButton = c(android.R.color.system_accent2_700)
            )
        } else {
            theme.copy(
                background = c(android.R.color.system_neutral1_10),
                divider = c(android.R.color.system_neutral2_200),
                normalKey = c(android.R.color.system_neutral1_50),
                specialKey = c(android.R.color.system_accent2_100),
                textAndIcons = c(android.R.color.system_neutral1_900),
                ledInactive = c(android.R.color.system_neutral2_200),
                ledActive = c(android.R.color.system_accent1_600),
                ledLocked = c(android.R.color.system_accent3_600),
                accent = c(android.R.color.system_accent1_600),
                cursorSwipe = c(android.R.color.system_accent1_600),
                keyPopup = c(android.R.color.system_accent2_100),
                keyPopupSelected = c(android.R.color.system_accent1_600),
                suggestion = c(android.R.color.system_neutral1_50),
                statusBarButton = c(android.R.color.system_accent2_100)
            )
        }
    }
}
