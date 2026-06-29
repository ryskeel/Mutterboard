package com.example.mutterboard

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Optional cleanup pass that runs *after* Whisper, on the cloud path only.
 *
 * Takes raw dictation text and asks a fast Groq chat model to strip filler
 * words and disfluencies, fix punctuation, and correct the occasional word the
 * speech model clearly misheard — without changing the person's wording, voice,
 * or meaning. The whole product here is the prompt: it's tuned for *casual
 * text messaging*, so the output should never read like an AI wrote it or like
 * it was pasted out of a document.
 *
 * Uses the same Groq API key and host as [GroqWhisperClient]; chat lives at a
 * sibling endpoint. On any failure ([refine] yields null) the caller is meant
 * to fall back to committing the raw Whisper text — a missed "um" is far better
 * than dropping the message.
 */
class GroqRefiner(private val apiKey: String) {

    private val client = OkHttpClient()

    /** Warm the TLS connection ahead of the request, mirroring the Whisper client. */
    fun warmUp() {
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

    /**
     * Clean [text] and deliver the result via [onResult] on an OkHttp thread.
     * A null result means the request failed; the caller should use the raw
     * text instead of dropping it.
     */
    fun refine(text: String, onResult: (String?) -> Unit) {
        // Examples live inside the system prompt (not as prior user/assistant
        // turns) and the transcript is the ONLY user turn. With multi-turn
        // examples a small model treats the exchange as a conversation to
        // continue and, on sparse/near-silent audio, will echo an example
        // sentence verbatim instead of cleaning the real input. One user turn
        // removes that failure mode.
        val messages = JSONArray().apply {
            put(message("system", SYSTEM_PROMPT))
            put(message("user", text))
        }
        val payload = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0)
            put("messages", messages)
        }
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    onResult(null)
                    return
                }
                onResult(parseContent(body))
            }
        })
    }

    fun close() {
        // evictAll() flushes and closes pooled TLS sockets — that's network I/O,
        // and close() is called from the IME's onDestroy()/refresh on the MAIN
        // thread, where StrictMode throws NetworkOnMainThreadException and takes
        // the whole process down. Tear the client down on a background thread.
        val doomed = client
        Thread {
            doomed.dispatcher.executorService.shutdown()
            doomed.connectionPool.evictAll()
        }.start()
    }

    private fun message(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private fun parseContent(body: String): String? = try {
        val content = JSONObject(body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
        // The model is told not to wrap its answer, but strip a stray pair of
        // surrounding quotes defensively so they never leak into the message.
        val unwrapped = stripWrappingQuotes(content)
        unwrapped.ifBlank { null }
    } catch (e: Exception) {
        null
    }

    private fun stripWrappingQuotes(s: String): String {
        if (s.length >= 2) {
            val first = s.first()
            val last = s.last()
            val isPair = (first == '"' && last == '"') ||
                (first == '“' && last == '”')
            if (isPair) return s.substring(1, s.length - 1).trim()
        }
        return s
    }

    companion object {
        // Fast and cheap — the right default for a keyboard where the refine
        // pass adds a second network round-trip on top of transcription.
        private const val MODEL = "llama-3.1-8b-instant"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        // The examples are embedded here as labeled Input/Output pairs (see the
        // note in refine() for why they aren't sent as conversation turns). The
        // anti-echo / anti-invention rule is deliberately blunt because a small
        // model is the one most likely to fabricate on sparse audio.
        private const val SYSTEM_PROMPT =
            "You clean up raw voice-dictation transcripts for a casual text-messaging " +
                "keyboard. The user's message is the single user turn. The text came from a " +
                "speech-to-text model and may contain filler words, false starts, missing or " +
                "wrong punctuation, and misheard words.\n\n" +
                "Return ONLY the cleaned version of that message. No preamble, no quotes, no " +
                "explanation.\n\n" +
                "Rules:\n" +
                "- Remove fillers and disfluencies (um, uh, false starts, accidental repeats) " +
                "and filler uses of \"like\", \"you know\", \"I mean\". Keep \"like\" or \"you know\" " +
                "when they carry casual meaning.\n" +
                "- Fix punctuation and capitalization. Use ONLY commas and periods. Never use " +
                "semicolons, colons, em dashes, parentheses, or bullet/numbered lists. If the " +
                "person is listing things, keep it as one natural sentence with commas, not a list.\n" +
                "- Keep the person's exact wording, slang, and casual voice. Do NOT rephrase to " +
                "sound more formal, polite, happy, or professional. Do NOT add or remove meaning. " +
                "Do NOT answer or react to the message.\n" +
                "- It must read like a real person texting on their phone, never like an AI wrote " +
                "it or like it was pasted from a document.\n" +
                "- Only change a word when you are confident the speech model misheard it and the " +
                "intended word is obvious from context, e.g. a homophone, or a name or term that " +
                "doesn't fit: \"Quad Code\" in a coding chat is \"Claude Code\", \"the Claude\" for " +
                "storage is \"the cloud\". When unsure, leave it exactly as is.\n" +
                "- CRITICAL: only clean up the exact message the user sent. Never add content that " +
                "was not in it, and never output any of the example sentences below — they exist " +
                "only to show the style. If the message is empty, only noise, or unintelligible, " +
                "return it unchanged.\n\n" +
                "Examples (each shows an Input and its cleaned Output):\n\n" +
                "Input: um yeah i was thinking we could just like push this to the Claude tonight " +
                "and uh see if it actually works\n" +
                "Output: Yeah I was thinking we could just push this to the cloud tonight and see " +
                "if it actually works.\n\n" +
                "Input: can you grab a few things from the store milk eggs bread and uh maybe some " +
                "coffee too\n" +
                "Output: Can you grab a few things from the store. Milk, eggs, bread, and maybe " +
                "some coffee too.\n\n" +
                "Input: im running like ten minutes late sorry i got stuck behind this super slow " +
                "truck on the highway\n" +
                "Output: I'm running like ten minutes late, sorry. I got stuck behind this super " +
                "slow truck on the highway.\n\n" +
                "Now clean up the user's message and output only the result."
    }
}
