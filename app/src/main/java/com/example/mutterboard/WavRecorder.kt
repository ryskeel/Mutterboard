package com.example.mutterboard

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The finished capture: the trimmed WAV, plus the same audio as Ogg/Opus when
 * it was stream-encoded during recording (cloud path only; null when streaming
 * was off, unsupported, or failed). Both files are cut at the same
 * trailing-silence trim point. The caller owns and deletes both.
 */
data class Recording(val wav: File, val opus: File?)

class WavRecorder(private val cacheDir: File) {

    @Volatile private var capturing = false
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var pcmFile: File? = null
    private var opusEncoder: StreamingOpusEncoder? = null
    @Volatile private var peakAmplitude: Int = 0

    fun currentPeak(): Int = peakAmplitude

    /**
     * [streamOpus] turns on the parallel Ogg/Opus encode ([StreamingOpusEncoder])
     * so the cloud upload needs no post-Stop compression. The offline path
     * passes false — Parakeet consumes the WAV.
     */
    @SuppressLint("MissingPermission")
    fun start(streamOpus: Boolean = false): Boolean {
        if (capturing) return false

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer <= 0) {
            Log.e(TAG, "Invalid min buffer size: $minBuffer")
            return false
        }
        val bufferSize = minBuffer * 2

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord constructor failed", e)
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${record.state})")
            record.release()
            return false
        }

        val outputFile = File(cacheDir, "rec_${System.currentTimeMillis()}.pcm")
        pcmFile = outputFile
        audioRecord = record
        peakAmplitude = 0

        val encoder = if (streamOpus) {
            StreamingOpusEncoder.start(File(cacheDir, outputFile.nameWithoutExtension + ".ogg"))
        } else null
        opusEncoder = encoder

        record.startRecording()
        capturing = true

        captureThread = Thread {
            val buffer = ByteArray(bufferSize)
            val marginBytes = SAMPLE_RATE * TRIM_MARGIN_MS / 1000 * 2
            var total = 0L
            // Absolute byte offset of the last sample that cleared the silence
            // threshold — the live counterpart of trimmedLength()'s backward
            // scan, driving how far the streaming encoder may advance.
            var lastLoud = -1L
            // Running loudest sample of the whole recording, which the silence
            // threshold is derived from. Only grows, so the threshold only
            // tightens - and a frontier that lagged is harmless, since finish()
            // lets the encoder drain to the final trim point anyway.
            var runningPeak = 0
            FileOutputStream(outputFile).use { out ->
                while (capturing) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        out.write(buffer, 0, read)
                        var localPeak = 0
                        var i = 0
                        while (i < read - 1) {
                            val lo = buffer[i].toInt() and 0xFF
                            val hi = buffer[i + 1].toInt()
                            val sample = (hi shl 8) or lo
                            val signed = if (sample > 32767) sample - 65536 else sample
                            val abs = if (signed < 0) -signed else signed
                            if (abs > localPeak) localPeak = abs
                            i += 2
                        }
                        if (localPeak > runningPeak) runningPeak = localPeak
                        // Second pass, because the threshold depends on the peak
                        // this buffer may have just raised.
                        val threshold = silenceThreshold(runningPeak)
                        var lastLoudInBuf = -1
                        i = 0
                        while (i < read - 1) {
                            val lo = buffer[i].toInt() and 0xFF
                            val hi = buffer[i + 1].toInt()
                            val sample = (hi shl 8) or lo
                            val signed = if (sample > 32767) sample - 65536 else sample
                            val abs = if (signed < 0) -signed else signed
                            if (abs > threshold) lastLoudInBuf = i
                            i += 2
                        }
                        peakAmplitude = localPeak
                        if (encoder != null) {
                            if (lastLoudInBuf >= 0) lastLoud = total + lastLoudInBuf
                            val frontier = if (lastLoud >= 0) lastLoud + 2 + marginBytes else 0L
                            encoder.feed(buffer, read, frontier)
                        }
                        total += read
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord.read error: $read")
                        break
                    }
                }
            }
        }.apply { start() }
        return true
    }

    fun stopAndFinalize(): Recording? {
        if (!capturing && audioRecord == null) return null
        capturing = false
        captureThread?.join(1000)
        captureThread = null

        val record = audioRecord
        audioRecord = null
        try { record?.stop() } catch (_: Exception) {}
        record?.release()

        val pcm = pcmFile
        pcmFile = null
        peakAmplitude = 0
        val encoder = opusEncoder
        opusEncoder = null

        if (pcm == null || !pcm.exists() || pcm.length() == 0L) {
            pcm?.delete()
            encoder?.cancel()
            return null
        }

        val pcmBytes = pcm.readBytes()
        // Whisper hallucinates on trailing silence — it emits caption-style
        // sign-offs ("Thank you") and, when a vocab prompt is set, bleeds the
        // prompt words into garbage. So trim the dead air off the end down to a
        // short natural margin instead of feeding it the full silent tail (the
        // post-Stop ambient run-off) plus a block of appended zero-silence.
        val dataSize = trimmedLength(pcmBytes)
        val wav = writeWav(pcm.nameWithoutExtension, pcmBytes, dataSize)
        pcm.delete()
        if (BuildConfig.DEBUG) {
            fun ms(bytes: Int) = bytes * 1000 / (SAMPLE_RATE * 2)
            val peak = peakOf(pcmBytes)
            Log.i(
                TAG,
                "trimmed trailing silence: ${ms(pcmBytes.size)}ms -> ${ms(dataSize)}ms " +
                    "(peak=$peak threshold=${silenceThreshold(peak)})"
            )
        }
        // Seal the streamed Opus at the same trim point. Normally only the
        // ~150ms margin is left to encode, so this returns almost immediately.
        val opus = encoder?.finish(dataSize.toLong())
        return Recording(wav, opus)
    }

    fun cancel() {
        capturing = false
        captureThread?.join(500)
        captureThread = null
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null
        opusEncoder?.cancel()
        opusEncoder = null
        pcmFile?.delete()
        pcmFile = null
        peakAmplitude = 0
    }

    private fun writeWav(baseName: String, pcmBytes: ByteArray, dataSize: Int): File {
        val wav = File(cacheDir, "$baseName.wav")
        val header = wavHeader(dataSize, SAMPLE_RATE, channels = 1, bitsPerSample = 16)
        FileOutputStream(wav).use { out ->
            out.write(header)
            out.write(pcmBytes, 0, dataSize)
        }
        return wav
    }

    /**
     * Length in bytes of [pcm] with trailing near-silence removed. Scans back
     * from the end for the last 16-bit sample above [SILENCE_THRESHOLD] (real
     * speech), then keeps [TRIM_MARGIN_MS] of run-off after it so the final word
     * isn't clipped. If the whole clip is below threshold (user said nothing),
     * the length is returned unchanged and the blank result is handled upstream.
     */
    private fun trimmedLength(pcm: ByteArray): Int {
        val marginBytes = SAMPLE_RATE * TRIM_MARGIN_MS / 1000 * 2
        val threshold = silenceThreshold(peakOf(pcm))
        // Even index of the last sample; step down two bytes (one sample) at a time.
        var i = (pcm.size and 1.inv()) - 2
        while (i >= 0) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val signed = if (sample > 32767) sample - 65536 else sample
            if ((if (signed < 0) -signed else signed) > threshold) {
                // Keep through this sample plus the run-off margin, clamped to size.
                return minOf(pcm.size, i + 2 + marginBytes)
            }
            i -= 2
        }
        return pcm.size
    }

    /** Loudest absolute sample in [pcm], the scale the trim threshold is set against. */
    private fun peakOf(pcm: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i < pcm.size - 1) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val signed = if (sample > 32767) sample - 65536 else sample
            val abs = if (signed < 0) -signed else signed
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }

    /**
     * Where speech stops and room tone starts, for THIS recording.
     *
     * A fixed threshold assumes a fixed speaking volume, and that assumption
     * failed in the wild: held at arm's length and talking quietly, a whole
     * dictation can sit near the old flat 350, so the backward scan found its
     * last "loud" sample seconds before the user actually stopped and deleted
     * the rest as silence. One measured case lost 3.2s of a 4.5s recording and
     * left Whisper hallucinating on the fragment.
     *
     * Scaling to the recording's own peak tracks the speaker instead: loud audio
     * lands near the old threshold, quiet audio gets a quiet threshold. The floor
     * stops a recording of pure room tone from setting a threshold so low that
     * nothing is ever trimmed, which is the failure the trim exists to prevent.
     */
    private fun silenceThreshold(peak: Int): Int =
        maxOf(SILENCE_FLOOR, peak * SILENCE_PEAK_PERCENT / 100)

    private fun wavHeader(pcmSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + pcmSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(pcmSize)
        }.array()
    }

    companion object {
        private const val TAG = "Mutterboard"
        // Run-off kept after the last detected speech so the final word/phoneme
        // isn't clipped. Replaces the old 500ms of appended zero-silence, which
        // was a Whisper hallucination trigger.
        private const val TRIM_MARGIN_MS = 150
        // The silence threshold is a percentage of the recording's own peak
        // rather than a flat amplitude, so it follows how loudly the user
        // actually spoke. 2% puts a normal close-mic dictation (peak ~20000)
        // near the old flat 350 this replaced.
        private const val SILENCE_PEAK_PERCENT = 2
        // Absolute floor on that threshold (0..32767), so a recording with no
        // speech in it can still be trimmed. Below the room tone the
        // VOICE_RECOGNITION source lets through, well below soft speech.
        // Tune both against the "trimmed trailing silence" debug log.
        private const val SILENCE_FLOOR = 150
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
