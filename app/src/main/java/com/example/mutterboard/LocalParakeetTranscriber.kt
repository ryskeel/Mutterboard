package com.example.mutterboard

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors

/**
 * On-device transcription using NVIDIA Parakeet (TDT 0.6B) via sherpa-onnx.
 *
 * The recognizer is heavy to construct (it loads a ~622 MB int8 encoder), so it
 * is built lazily on the first transcription and kept alive for reuse. All work
 * runs on a single background thread — sherpa-onnx streams are not thread-safe.
 */
class LocalParakeetTranscriber(private val modelDir: File) : Transcriber {

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var recognizer: OfflineRecognizer? = null

    private fun ensureRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder.int8.onnx").absolutePath,
                    joiner = File(modelDir, "joiner.int8.onnx").absolutePath,
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                modelType = "nemo_transducer",
                numThreads = 2,
            ),
        )
        // assetManager = null -> load models from the absolute file paths above.
        return OfflineRecognizer(config = config).also { recognizer = it }
    }

    override fun transcribe(audioFile: File, onResult: (String?) -> Unit) {
        executor.execute {
            val text = runCatching {
                val samples = readWavToFloat(audioFile)
                val rec = ensureRecognizer()
                // Parakeet is non-streaming: it processes the whole utterance at
                // once, so memory/compute grow with length. Split long audio into
                // bounded chunks (cut at quiet points) to stay within RAM.
                val bounds = chunkBoundaries(samples)
                val parts = ArrayList<String>(bounds.size - 1)
                for (k in 0 until bounds.size - 1) {
                    val chunk = samples.copyOfRange(bounds[k], bounds[k + 1])
                    val stream = rec.createStream()
                    stream.acceptWaveform(chunk, SAMPLE_RATE)
                    rec.decode(stream)
                    val part = rec.getResult(stream).text.trim()
                    stream.release()
                    if (part.isNotEmpty()) parts.add(part)
                }
                parts.joinToString(" ").trim()
            }.getOrElse {
                Log.e(TAG, "Local transcription failed", it)
                null
            }
            onResult(text)
        }
    }

    /**
     * Returns chunk boundary indices (inclusive start .. exclusive end pairs)
     * splitting [samples] into windows no longer than [MAX_CHUNK]. Each cut is
     * nudged to the lowest-energy point in a short search window before the
     * target so we split during a pause rather than mid-word.
     */
    private fun chunkBoundaries(samples: FloatArray): IntArray {
        val n = samples.size
        if (n <= MAX_CHUNK) return intArrayOf(0, n)

        val bounds = ArrayList<Int>()
        bounds.add(0)
        var start = 0
        while (n - start > MAX_CHUNK) {
            val target = start + MAX_CHUNK
            val searchStart = (target - SEARCH_WINDOW).coerceAtLeast(start + MIN_CHUNK)
            var cut = target
            var minEnergy = Float.MAX_VALUE
            var i = searchStart
            while (i < target) {
                var energy = 0f
                var j = i
                val end = (i + ENERGY_WINDOW).coerceAtMost(n)
                while (j < end) {
                    val a = samples[j]
                    energy += if (a < 0) -a else a
                    j++
                }
                if (energy < minEnergy) {
                    minEnergy = energy
                    cut = i
                }
                i += ENERGY_HOP
            }
            bounds.add(cut)
            start = cut
        }
        bounds.add(n)
        return bounds.toIntArray()
    }

    override fun close() {
        executor.execute {
            runCatching { recognizer?.release() }
            recognizer = null
        }
        executor.shutdown()
    }

    /** Reads a 16 kHz mono 16-bit PCM WAV (as written by [WavRecorder]) into [-1, 1] floats. */
    private fun readWavToFloat(file: File): FloatArray {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length <= WAV_HEADER_BYTES) return FloatArray(0)
            val dataBytes = (length - WAV_HEADER_BYTES).toInt()
            val bytes = ByteArray(dataBytes)
            raf.seek(WAV_HEADER_BYTES)
            raf.readFully(bytes)
            val sampleCount = dataBytes / 2
            val out = FloatArray(sampleCount)
            var b = 0
            for (i in 0 until sampleCount) {
                // little-endian 16-bit signed PCM -> [-1, 1]
                val lo = bytes[b].toInt() and 0xFF
                val hi = bytes[b + 1].toInt() // sign-extends the high byte
                val sample = (hi shl 8) or lo
                out[i] = sample / 32768f
                b += 2
            }
            return out
        }
    }

    companion object {
        private const val TAG = "MutterboardLocal"
        private const val WAV_HEADER_BYTES = 44L
        private const val SAMPLE_RATE = 16000

        // Chunking limits (in samples at 16 kHz) for long non-streaming audio.
        private const val MAX_CHUNK = SAMPLE_RATE * 15      // 15 s hard cap per chunk
        private const val MIN_CHUNK = SAMPLE_RATE * 5       // don't cut before 5 s
        private const val SEARCH_WINDOW = SAMPLE_RATE * 2   // look back 2 s for a pause
        private const val ENERGY_WINDOW = 400               // 25 ms energy frame
        private const val ENERGY_HOP = 160                  // 10 ms hop

        val MODEL_FILES = listOf(
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "joiner.int8.onnx",
            "tokens.txt",
        )

        /** True when every required model file is present in [modelDir]. */
        fun isModelReady(modelDir: File): Boolean =
            MODEL_FILES.all { File(modelDir, it).length() > 0 }
    }
}
