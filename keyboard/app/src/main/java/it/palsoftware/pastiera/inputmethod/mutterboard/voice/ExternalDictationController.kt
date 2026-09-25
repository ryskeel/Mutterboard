package it.palsoftware.pastiera.inputmethod.mutterboard.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import it.palsoftware.pastiera.inputmethod.CandidatesBarController

/**
 * Runs one Mutterboard dictation at a time from the physical keyboard's mic
 * button, and swaps the bar into its dictating look and back.
 *
 * Each dictation gets a fresh [ExternalDictation]. A cancelled one can still
 * have a transcript in flight, and the only way to be sure it never lands in the
 * field is to stop listening to it - so the old one is muted and destroyed
 * rather than reused.
 */
class ExternalDictationController(
    private val context: Context,
    private val inputConnection: () -> InputConnection?,
    private val bar: () -> CandidatesBarController,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var current: ExternalDictation? = null
    private var recording = false

    private val levelTicker = object : Runnable {
        override fun run() {
            val d = current ?: return
            if (!recording) return
            val level = d.level()
            bar().updateDictationLevel(level)
            // The mic button's red pulse speaks Android's rmsdB, -10..0.
            bar().updateMicrophoneAudioLevel(level * 10f - 10f)
            handler.postDelayed(this, LEVEL_INTERVAL_MS)
        }
    }

    /**
     * The mic button was pressed. Returns false when Mutterboard's dictation is
     * not installed, so the caller carries on with Android's recognizer.
     */
    fun micPressed(): Boolean {
        val factory = ExternalDictation.factory ?: return false
        val active = current
        if (active != null) {
            active.micTapped()
            return true
        }
        // Null until the factory returns: the session reports its idle state
        // from inside its own constructor, and that report is not for us.
        var dictation: ExternalDictation? = null
        val created = factory(context, object : ExternalDictation.Callbacks {
            private fun mine() = dictation != null && current === dictation

            override fun commit(text: String) {
                if (mine()) inputConnection()?.commitText(text, 1)
            }

            override fun onUpdate(update: ExternalDictation.Update) {
                if (mine()) render(update)
            }

            override fun onFinished() {
                if (mine()) finish()
            }
        })
        dictation = created
        current = created
        bar().showDictationStrip(true) { cancel() }
        bar().setMicrophoneButtonActive(true)
        created.start()
        return true
    }

    fun cancel() {
        current?.cancel()
        finish()
    }

    /** The keyboard hid. A recording stops; a transcript already on its way lands. */
    fun onHidden() {
        if (recording) cancel()
    }

    fun destroy() {
        finish()
    }

    private fun render(update: ExternalDictation.Update) {
        val wasRecording = recording
        recording = update.phase == ExternalDictation.Phase.RECORDING
        bar().setDictationCaption(update.message)
        // Stop stays a stop only while there is something to stop; from an
        // error it goes back to a mic, which retries.
        bar().setMicrophoneButtonActive(recording)
        if (recording && !wasRecording) handler.post(levelTicker)
    }

    private fun finish() {
        val d = current ?: return
        current = null
        recording = false
        handler.removeCallbacks(levelTicker)
        d.destroy()
        bar().showDictationStrip(false, null)
        bar().setMicrophoneButtonActive(false)
        bar().updateMicrophoneAudioLevel(-10f)
    }

    private companion object {
        const val LEVEL_INTERVAL_MS = 50L
    }
}
