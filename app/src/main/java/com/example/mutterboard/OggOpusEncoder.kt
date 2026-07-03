package com.example.mutterboard

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Re-encodes a recorded WAV as Ogg/Opus before upload.
 *
 * Raw 16 kHz mono WAV costs 32 KB per second of speech, and the timing logs
 * showed the Whisper leg growing with dictation length, i.e. upload-bound. At
 * [BIT_RATE] Opus is roughly 8x smaller with no meaningful ASR accuracy loss,
 * so long dictations stop paying a multi-second upload.
 *
 * Uses the platform MediaCodec Opus encoder and MediaMuxer's Ogg container,
 * both available from API 29; [encode] returns false on older devices or any
 * failure, and the caller uploads the WAV unchanged. The input must match the
 * [WavRecorder] output format: 16 kHz mono 16-bit PCM with a 44-byte header.
 */
object OggOpusEncoder {

    /**
     * Encodes [wav] into [out]. Returns true when [out] is a complete Ogg/Opus
     * file; on false, [out] has been deleted and the WAV should be used instead.
     */
    fun encode(wav: File, out: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            doEncode(wav, out)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Opus encode failed, falling back to WAV", e)
            out.delete()
            false
        }
    }

    private fun doEncode(wav: File, out: File) {
        val pcm = wav.readBytes()
        if (pcm.size <= WAV_HEADER_BYTES) throw IllegalArgumentException("WAV too short")

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)

            // Feed input as fast as the codec accepts it and drain output without
            // blocking. Blocking on dequeueOutputBuffer per frame serializes the
            // encode to near real time (~10s for an 18s dictation); the only
            // blocking wait allowed is for output after all input is queued.
            var track = -1
            var inPos = WAV_HEADER_BYTES
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val chunk = minOf(buf.capacity(), pcm.size - inPos)
                        if (chunk <= 0) {
                            codec.queueInputBuffer(
                                idx, 0, 0, ptsUs(inPos), MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            buf.put(pcm, inPos, chunk)
                            codec.queueInputBuffer(idx, 0, chunk, ptsUs(inPos), 0)
                            inPos += chunk
                        }
                    }
                }
                while (!outputDone) {
                    val outIdx = codec.dequeueOutputBuffer(
                        info, if (inputDone) EOS_DRAIN_TIMEOUT_US else 0L
                    )
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (outIdx >= 0) {
                        val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !codecConfig) {
                            muxer.writeSampleData(track, codec.getOutputBuffer(outIdx)!!, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    } else {
                        break
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            muxer?.release()
        }
    }

    /** Presentation time of the sample starting at byte [pcmOffset] of the file. */
    private fun ptsUs(pcmOffset: Int): Long {
        val samples = (pcmOffset - WAV_HEADER_BYTES).toLong() / 2
        return samples * 1_000_000L / SAMPLE_RATE
    }

    private const val TAG = "MutterboardOpus"
    private const val WAV_HEADER_BYTES = 44
    // Must match WavRecorder's output rate; Opus supports 16 kHz natively.
    private const val SAMPLE_RATE = 16_000
    // 32 kbps is comfortably transparent for speech recognition (ASR holds up
    // well below this) while still cutting the 256 kbps WAV by 8x.
    private const val BIT_RATE = 32_000
    private const val INPUT_TIMEOUT_US = 10_000L
    private const val EOS_DRAIN_TIMEOUT_US = 10_000L
}
