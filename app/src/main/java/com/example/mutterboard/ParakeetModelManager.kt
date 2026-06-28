package com.example.mutterboard

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Downloads and unpacks the Parakeet model used by [LocalParakeetTranscriber].
 *
 * The model ships as a ~630 MB .tar.bz2 from the sherpa-onnx releases. It is far
 * too large to bundle in the APK, so it is fetched on first use into app storage
 * and only the files [LocalParakeetTranscriber.MODEL_FILES] are kept.
 */
class ParakeetModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient()

    val modelDir: File = File(appContext.filesDir, "models/parakeet")

    fun isReady(): Boolean = LocalParakeetTranscriber.isModelReady(modelDir)

    /**
     * Removes the downloaded model from internal storage to reclaim the ~630 MB.
     * Returns true if no usable model remains afterwards.
     */
    fun deleteModel(): Boolean {
        modelDir.deleteRecursively()
        return !isReady()
    }

    sealed interface Progress {
        data class Downloading(val fraction: Float) : Progress
        object Extracting : Progress
        object Done : Progress
        data class Failed(val message: String) : Progress
    }

    /**
     * Downloads + extracts the model, reporting [Progress] on a background thread.
     * Callers must marshal UI updates to the main thread themselves.
     */
    fun download(onProgress: (Progress) -> Unit) {
        executor.execute {
            val tmp = File(appContext.cacheDir, "parakeet-model.tar.bz2")
            try {
                modelDir.mkdirs()
                downloadFile(MODEL_URL, tmp, onProgress)
                onProgress(Progress.Extracting)
                extract(tmp, modelDir)
                tmp.delete()
                if (isReady()) {
                    onProgress(Progress.Done)
                } else {
                    onProgress(Progress.Failed("Model files missing after extraction"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Model download failed", e)
                tmp.delete()
                onProgress(Progress.Failed(e.message ?: "Download failed"))
            }
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Progress) -> Unit) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("Empty response")
            val total = body.contentLength()
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var downloaded = 0L
                    var lastReported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(Progress.Downloading(pct / 100f))
                            }
                        }
                    }
                }
            }
        }
    }

    /** Extracts the wanted model files, flattening the archive's top-level directory. */
    private fun extract(archive: File, dest: File) {
        val wanted = LocalParakeetTranscriber.MODEL_FILES.toSet()
        archive.inputStream().buffered().use { fileIn ->
            BZip2CompressorInputStream(fileIn).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    while (true) {
                        val entry = tarIn.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = File(entry.name).name
                        if (name !in wanted) continue
                        File(dest, name).outputStream().use { out ->
                            tarIn.copyTo(out)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MutterboardModel"
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2"
    }
}
