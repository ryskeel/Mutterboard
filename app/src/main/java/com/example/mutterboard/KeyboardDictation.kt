package com.example.mutterboard

import android.content.Context
import it.palsoftware.pastiera.inputmethod.mutterboard.voice.ExternalDictation

/**
 * The physical keyboard's mic button, running on [DictationSession].
 *
 * A third host alongside the dictation keyboard and the overlay. Like the
 * overlay it draws its own UI (the keyboard's dictation strip) from the
 * session's snapshots, so it never inflates the session's views.
 */
class KeyboardDictation(
    context: Context,
    private val callbacks: ExternalDictation.Callbacks,
) : ExternalDictation, DictationSession.Host {

    private val session = DictationSession(context, this)

    init {
        session.onUpdate = { callbacks.onUpdate(it.toUpdate()) }
    }

    override fun start() = session.onShown()

    override fun micTapped() = session.micTapped()

    override fun cancel() = session.cancelTapped()

    override fun level(): Float = session.micLevel()

    override fun onHidden() = session.onHidden()

    override fun destroy() {
        session.onUpdate = null
        session.destroy()
    }

    override fun commit(text: String) = callbacks.commit(text)

    override fun dismiss() = callbacks.onFinished()

    private fun DictationSession.Snapshot.toUpdate() = ExternalDictation.Update(
        phase = when (state) {
            DictationSession.State.RECORDING -> ExternalDictation.Phase.RECORDING
            DictationSession.State.TRANSCRIBING -> ExternalDictation.Phase.TRANSCRIBING
            else -> ExternalDictation.Phase.NEEDS_ATTENTION
        },
        message = message,
    )
}
