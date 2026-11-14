package com.example.mutterboard

/**
 * Which refine pass runs after transcription on the cloud path.
 *
 * [DEFAULT] is the original [GroqRefiner] output — correctly punctuated,
 * correctly capitalized prose. [CASUAL] routes to [CasualRefiner] instead, which
 * makes the same edit with no period at the end and lowercase place and brand
 * names, the way a text reads.
 *
 * Toggled from the keyboard itself and persisted, mirroring [Engine].
 */
enum class RefineMode(val prefValue: String) {
    DEFAULT("default"),
    CASUAL("casual");

    companion object {
        /**
         * Casual mode is switched off, not deleted (Ry, 2026-11-14). Default
         * broke long rambles into sentences better than every casual prompt
         * tried, and the one casual habit worth keeping everywhere, no period
         * at the end, now happens in code for every dictation
         * ([DictationSession.commitAndFinish]). Off, the session always runs
         * [GroqRefiner], never builds [CasualRefiner], and every toggle hides.
         * The stored mode pref is left alone, so turning this back on puts
         * whoever was in Casual back in it.
         */
        const val CASUAL_ENABLED = false

        fun fromPref(value: String?): RefineMode =
            entries.firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}
