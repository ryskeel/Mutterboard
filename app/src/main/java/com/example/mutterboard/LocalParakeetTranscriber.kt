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
                val stream = rec.createStream()
                stream.acceptWaveform(samples, 16000)
                rec.decode(stream)
                val result = rec.getResult(stream)
                stream.release()
                result.text.trim()
            }.getOrElse {
                Log.e(TAG, "Local transcription failed", it)
                null
            }
            onResult(text)
        }
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
