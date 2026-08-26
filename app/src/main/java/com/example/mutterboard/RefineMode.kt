package com.example.mutterboard

/**
 * Which refine pass runs after transcription on the cloud path.
 *
 * [DEFAULT] is the original [GroqRefiner] output — correctly punctuated,
 * correctly capitalized prose. [CASUAL] routes to [CasualRefiner] instead, which
 * makes the same edit but renders it in the way the user actually texts.
 *
 * Toggled from the keyboard itself and persisted, mirroring [Engine].
 */
enum class RefineMode(val prefValue: String) {
    DEFAULT("default"),
    CASUAL("casual");

    companion object {
        fun fromPref(value: String?): RefineMode =
            entries.firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}
