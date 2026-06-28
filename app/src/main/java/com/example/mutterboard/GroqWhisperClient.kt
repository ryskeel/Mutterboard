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
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("language", "en")
            .addFormDataPart("temperature", "0")
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaType())
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
}
