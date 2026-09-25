package it.palsoftware.pastiera.inputmethod.mutterboard.voice

import android.content.Context
import android.graphics.Color
import android.os.Build

/**
 * The colours the overlay's mist is painted in, for a keyboard that has no
 * Material theme to read them from. The overlay takes them from Material You's
 * dark scheme; these are the same system palette slots, so the two match.
 */
internal object DictationLook {
    class Palette(
        val primaryContainer: Int,
        val secondaryContainer: Int,
        val tertiaryContainer: Int,
        val primary: Int,
        val secondary: Int,
        val tertiary: Int,
    )

    fun palette(context: Context): Palette {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fun c(id: Int) = context.getColor(id)
            return Palette(
                primaryContainer = c(android.R.color.system_accent1_700),
                secondaryContainer = c(android.R.color.system_accent2_700),
                tertiaryContainer = c(android.R.color.system_accent3_700),
                primary = c(android.R.color.system_accent1_200),
                secondary = c(android.R.color.system_accent2_200),
                tertiary = c(android.R.color.system_accent3_200),
            )
        }
        // Mutterboard's own coral family, for phones without dynamic colour.
        return Palette(
            primaryContainer = Color.rgb(0x8C, 0x33, 0x22),
            secondaryContainer = Color.rgb(0x5D, 0x40, 0x37),
            tertiaryContainer = Color.rgb(0x5A, 0x44, 0x1A),
            primary = Color.rgb(0xFF, 0xB4, 0xA3),
            secondary = Color.rgb(0xE7, 0xBD, 0xB2),
            tertiary = Color.rgb(0xDF, 0xC3, 0x8C),
        )
    }
}
