package com.example.mutterboard

import java.io.File

/**
 * Turns a recorded 16 kHz mono WAV file into text.
 *
 * Implementations may run in the cloud ([GroqWhisperClient]) or fully on-device
 * ([LocalParakeetTranscriber]). [transcribe] returns immediately; the result is
 * delivered later via [onResult] on an unspecified thread, so callers must
 * marshal back to the main thread themselves. A null result means failure.
 */
interface Transcriber {
    fun transcribe(audioFile: File, onResult: (String?) -> Unit)

    /** Release any native/network resources. Safe to call more than once. */
    fun close() {}
}

/** Which transcription engine the user has selected. */
enum class Engine(val prefValue: String) {
    CLOUD("cloud"),
    LOCAL("local");

    companion object {
        fun fromPref(value: String?): Engine =
            entries.firstOrNull { it.prefValue == value } ?: CLOUD
    }
}
