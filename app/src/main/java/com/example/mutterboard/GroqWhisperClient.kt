package com.example.mutterboard

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

class GroqWhisperClient(private val apiKey: String) : Transcriber {

    private val client = OkHttpClient()

    /**
     * Optional vocabulary hint passed to Whisper as the `prompt` field. Whisper
     * biases its decoding toward terms that appear here, so the user's custom
     * words (names, brands, jargon) come back spelled the way they expect. Set by
     * the IME from the saved word list; null/blank means no hint. Volatile because
     * it's updated from the main thread and read on an OkHttp worker thread.
     */
    @Volatile
    var vocabularyPrompt: String? = null

    /**
     * Open a pooled TLS connection to Groq ahead of the upload so the actual
     * transcription POST reuses an already-established connection instead of
     * paying the TCP+TLS handshake. Fired when recording starts; fire-and-forget,
     * and uses the same OkHttpClient (so the same connection pool) as transcribe.
     * No auth needed — this only warms the connection, not a real API call.
     */
    override fun warmUp() {
        val request = Request.Builder()
            .url("https://api.groq.com/")
            .head()
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    override fun transcribe(audioFile: File, onResult: (String?) -> Unit) {
        // Compress before upload: raw WAV is 32 KB/s and made long dictations
        // upload-bound; Opus cuts that ~8x. But the platform encoder measures
        // ~45ms per second of audio (its complexity hint is ignored), so on
        // short clips encoding costs about what the smaller upload saves; skip
        // it below the threshold. Encoding blocks, so it runs off the caller's
        // thread. On failure or pre-API-29 the WAV is uploaded as before.
        Thread {
            val opus = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".ogg")
            val encodeStart = android.os.SystemClock.elapsedRealtime()
            val useOpus = audioFile.length() > OPUS_MIN_WAV_BYTES &&
                OggOpusEncoder.encode(audioFile, opus)
            if (useOpus) {
                android.util.Log.d(
                    TAG,
                    "opus encode took ${android.os.SystemClock.elapsedRealtime() - encodeStart}ms, " +
                        "${audioFile.length()} -> ${opus.length()} bytes"
                )
            }
            val upload = if (useOpus) opus else audioFile
            val mediaType = if (useOpus) "audio/ogg" else "audio/wav"
            upload(upload, mediaType) { text ->
                opus.delete()
                onResult(text)
            }
        }.start()
    }

    private fun upload(audioFile: File, mediaType: String, onResult: (String?) -> Unit) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("language", "en")
            .addFormDataPart("temperature", "0")
            .apply {
                vocabularyPrompt?.takeIf { it.isNotBlank() }?.let {
                    addFormDataPart("prompt", it)
                }
            }
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody(mediaType.toMediaType())
            )
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        onResult(JSONObject(body).getString("text").trim())
                    } catch (e: Exception) {
                        onResult(null)
                    }
                } else {
                    onResult(null)
                }
            }
        })
    }

    companion object {
        private const val TAG = "MutterboardWhisper"
        // Compress only above ~10s of audio (32 KB/s WAV). Below this the fixed
        // encode cost rivals the upload savings on a decent connection; above it
        // compression wins, and by a lot on long dictations or weak signal.
        private const val OPUS_MIN_WAV_BYTES = 320_000L
    }
}
