package it.palsoftware.pastiera.inputmethod.mutterboard.voice

import android.content.Context

/**
 * Mutterboard's dictation, as the physical keyboard sees it.
 *
 * This module is a library the Mutterboard app depends on, so it cannot reach
 * the app's DictationSession directly. The app installs [factory] when its
 * process starts; while it is null (Pastiera built on its own) the mic key keeps
 * using Android's speech recognizer as it always did.
 *
 * Everything under this package is Mutterboard's, not Pastiera's, which keeps
 * the edits to Pastiera's own files down to the few lines that call in here.
 */
interface ExternalDictation {

    enum class Phase {
        RECORDING,
        TRANSCRIBING,

        /** Stopped short of recording: no key, no permission, no speech heard. */
        NEEDS_ATTENTION,
    }

    /** [message] is the caption to show, or null for none. */
    data class Update(val phase: Phase, val message: String?)

    interface Callbacks {
        /** Finished text, trailing space included. Insert it as-is. */
        fun commit(text: String)

        fun onUpdate(update: Update)

        /** The dictation is over, committed or not. Put the bar back. */
        fun onFinished()
    }

    /** Starts listening. */
    fun start()

    /** The stop button: stops and transcribes, or retries from an error. */
    fun micTapped()

    /** Throws the recording away. */
    fun cancel()

    /** Live mic level, 0..1. */
    fun level(): Float

    /** The keyboard went away mid-dictation; never leave the mic hot. */
    fun onHidden()

    fun destroy()

    companion object {
        @Volatile
        var factory: ((Context, Callbacks) -> ExternalDictation)? = null
    }
}
