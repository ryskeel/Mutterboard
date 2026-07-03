package com.example.mutterboard

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.ArrayDeque

/**
 * Encodes PCM into an Ogg/Opus file WHILE it is still being recorded, so the
 * upload compression that used to run after Stop (1-3s of dead time on long
 * dictations, see [OggOpusEncoder]) hides behind the user's speech instead.
 * With streaming there is no minimum-length gate either: the per-buffer cost
 * is a few percent of one core, and finalizing at Stop is tens of ms.
 *
 * Life cycle: [start] creates the codec and an encoder thread; the capture
 * thread calls [feed] with each PCM buffer; [finish] seals the stream at the
 * final trimmed length and returns the completed file, or null on any failure,
 * in which case the caller falls back to uploading the WAV; [cancel] aborts
 * and deletes the output.
 *
 * The frontier passed to [feed] exists because of the trailing-silence trim:
 * audio after the last speech must not reach Whisper (hallucination trigger,
 * see WavRecorder.trimmedLength), and encoded bytes can't be taken back. So
 * the recorder only ever authorizes encoding up to (last speech + margin);
 * silent audio queues up unencoded and is simply dropped at [finish] if no
 * speech ever follows it. After a long mid-dictation pause the encoder briefly
 * lags real time and catches up at ~20x while the user keeps talking.
 */
class StreamingOpusEncoder private constructor(
    private val out: File,
    private val codec: MediaCodec,
) {
    private class Chunk(val data: ByteArray, val start: Long)

    private val lock = Object()
    private val pending = ArrayDeque<Chunk>()
    // Bytes of the head chunk already handed to the codec (partial feeds happen
    // when the frontier lands mid-chunk).
    private var headConsumed = 0
    private var submitted = 0L
    private var frontier = 0L
    private var finalLen = -1L
    @Volatile private var canceled = false
    @Volatile private var failed = false

    // Codec-side state, touched only on the encoder thread.
    private var bytesFed = 0L
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var track = -1
    private val info = MediaCodec.BufferInfo()

    private val thread = Thread(::run, "OpusStream")

    /**
     * Queue [len] bytes of PCM for encoding. [allowedFrontier] is the absolute
     * stream offset encoding may advance to (last speech + margin); it only
     * ever moves forward. Called on the capture thread; never blocks on the
     * codec, so capture can't stall.
     */
    fun feed(data: ByteArray, len: Int, allowedFrontier: Long) {
        synchronized(lock) {
            if (canceled || finalLen >= 0) return
            pending.add(Chunk(data.copyOf(len), submitted))
            submitted += len
            frontier = maxOf(frontier, minOf(allowedFrontier, submitted))
            lock.notifyAll()
        }
    }

    /**
     * Seal the stream at [trimmedLen] (the same trimmed byte length the WAV is
     * cut to) and wait for the encoder to drain. Returns the finished Ogg file,
     * or null on failure/timeout — callers then upload the WAV instead.
     */
    fun finish(trimmedLen: Long): File? {
        val begin = SystemClock.elapsedRealtime()
        synchronized(lock) {
            finalLen = minOf(trimmedLen, submitted)
            lock.notifyAll()
        }
        thread.join(FINISH_TIMEOUT_MS)
        if (thread.isAlive) {
            Log.w(TAG, "stream encode didn't finish in time, falling back to WAV")
            cancel()
            return null
        }
        if (failed || out.length() == 0L) {
            out.delete()
            return null
        }
        Log.d(TAG, "opus finalize took ${SystemClock.elapsedRealtime() - begin}ms, ${out.length()} bytes")
        return out
    }

    /** Abort and delete the output. Safe from any thread. */
    fun cancel() {
        synchronized(lock) {
            canceled = true
            lock.notifyAll()
        }
    }

    private fun run() {
        try {
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)

            while (true) {
                var slice: ByteArray? = null
                var sliceOff = 0
                var sliceLen = 0
                synchronized(lock) {
                    while (slice == null) {
                        if (canceled) throw InterruptedException("canceled")
                        val head = pending.peekFirst()
                        val limit = if (finalLen >= 0) finalLen else frontier
                        if (head != null) {
                            val pos = head.start + headConsumed
                            val avail = (minOf(head.start + head.data.size, limit) - pos).toInt()
                            if (avail > 0) {
                                slice = head.data
                                sliceOff = headConsumed
                                sliceLen = avail
                                headConsumed += avail
                                if (headConsumed == head.data.size) {
                                    pending.pollFirst()
                                    headConsumed = 0
                                }
                                continue
                            }
                        }
                        // Nothing feedable. Once finish() has set the final
                        // length, everything at or past it is trailing silence
                        // to drop — seal the stream.
                        if (finalLen >= 0) return@synchronized
                        lock.wait()
                    }
                }
                if (slice == null) break
                feedPcm(slice!!, sliceOff, sliceLen)
            }
            sendEosAndDrain()
        } catch (e: Exception) {
            if (!canceled) Log.w(TAG, "stream encode failed", e)
            failed = true
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { failed = true }
            muxer?.release()
            if (failed || canceled) out.delete()
        }
    }

    private fun feedPcm(data: ByteArray, off: Int, len: Int) {
        var pos = off
        val end = off + len
        while (pos < end) {
            if (canceled) throw InterruptedException("canceled")
            val idx = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                val chunk = minOf(buf.capacity(), end - pos)
                buf.clear()
                buf.put(data, pos, chunk)
                codec.queueInputBuffer(idx, 0, chunk, ptsUs(bytesFed), 0)
                bytesFed += chunk
                pos += chunk
            }
            drain(0L)
        }
    }

    private fun sendEosAndDrain() {
        while (true) {
            val idx = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, ptsUs(bytesFed), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(0L)
        }
        val deadline = SystemClock.elapsedRealtime() + EOS_DEADLINE_MS
        while (!drain(DRAIN_TIMEOUT_US)) {
            if (SystemClock.elapsedRealtime() > deadline) {
                throw IllegalStateException("EOS drain timed out")
            }
        }
    }

    /** Pull encoded output while it's available; true once EOS has been seen. */
    private fun drain(timeoutUs: Long): Boolean {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer!!.addTrack(codec.outputFormat)
                muxer!!.start()
                muxerStarted = true
            } else if (idx >= 0) {
                val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (info.size > 0 && !codecConfig) {
                    muxer!!.writeSampleData(track, codec.getOutputBuffer(idx)!!, info)
                }
                codec.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
            } else {
                return false
            }
        }
    }

    /** Presentation time of the next sample after [fed] bytes of 16-bit mono PCM. */
    private fun ptsUs(fed: Long): Long = fed / 2 * 1_000_000L / SAMPLE_RATE

    companion object {
        /**
         * Create the encoder and start its thread, or null when unsupported
         * (Ogg muxing needs API 29) or codec setup fails — the caller records
         * WAV-only as before.
         */
        fun start(out: File): StreamingOpusEncoder? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return try {
                val format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1
                ).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                }
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                StreamingOpusEncoder(out, codec).also { it.thread.start() }
            } catch (e: Exception) {
                Log.w(TAG, "streaming opus unavailable, will upload WAV", e)
                null
            }
        }

        private const val TAG = "MutterboardOpus"
        // Must match WavRecorder's output rate; Opus supports 16 kHz natively.
        private const val SAMPLE_RATE = 16_000
        // Same rationale as OggOpusEncoder: transparent for ASR, ~8x smaller.
        private const val BIT_RATE = 32_000
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val EOS_DEADLINE_MS = 2_000L
        // finish() runs on the main thread at Stop; normally only ~150ms of
        // audio remains to encode, so this is pure safety margin for the
        // degenerate cases (all-silent clip, stop right after a long pause).
        private const val FINISH_TIMEOUT_MS = 4_000L
    }
}
