package it.palsoftware.pastiera.inputmethod.mutterboard.voice

import android.content.Context
import android.graphics.Color
import android.os.Build

/**
 * The colours the overlay's mist is painted in, for a keyboard that has no
 * Material theme to read them from. Same Material You families as the overlay,
 * but two tones lighter: the overlay's mist sits on a grey surface, this one on
 * a black bar, where the overlay's tones read as faint grey smudges (seen in a
 * screen recording - the poof was running and nobody could see it).
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
                primaryContainer = c(android.R.color.system_accent1_500),
                secondaryContainer = c(android.R.color.system_accent2_500),
                tertiaryContainer = c(android.R.color.system_accent3_500),
                primary = c(android.R.color.system_accent1_300),
                secondary = c(android.R.color.system_accent2_300),
                tertiary = c(android.R.color.system_accent3_300),
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
