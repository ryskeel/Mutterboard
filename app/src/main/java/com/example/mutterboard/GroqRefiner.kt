package com.example.mutterboard

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Optional cleanup pass that runs *after* Whisper, on the cloud path only.
 *
 * The task is framed as an *edit*, not a rewrite: strip filler words and
 * disfluencies and fix punctuation/capitalization, while keeping every surviving
 * word exactly as the user said it. Whisper V3 Turbo already gets the words
 * right; a full-rewrite pass just gives the model room to reword and reframe the
 * message, which is the failure mode this is tuned to avoid.
 *
 * The refined text is committed whenever the model returns one; [refine] only
 * yields null (raw fallback) on a genuine API failure or empty response. The
 * model proved reliable enough in testing that the user prefers its output even
 * on the occasional reorder/reword over ever getting the raw transcript back.
 *
 * [isCleanEdit] is kept purely as an *advisory* signal: in debug builds it labels
 * each result `clean-edit` or `rewrite` in the log (see [logDiff]) so rewrites
 * can still be watched and the prompt tuned, but it no longer blocks anything. If
 * rewrites ever start changing meaning, re-gating on it is a one-line change.
 *
 * Uses the same Groq API key and host as [GroqWhisperClient]; chat lives at a
 * sibling endpoint.
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
                val refined = parseContent(body)
                // Always commit the refined text when we got one (null only on a
                // failed/empty parse, which falls back to raw). The clean-edit
                // check is advisory now — computed only to label the debug log so
                // rewrites stay visible; it no longer gates the output.
                if (BuildConfig.DEBUG) {
                    val verdict = when {
                        refined == null -> "empty"
                        isCleanEdit(text, refined) -> "clean-edit"
                        else -> "rewrite"
                    }
                    logDiff(text, refined, verdict)
                }
                onResult(refined)
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

    /**
     * True if [refined] is a "clean edit" of [raw]: it may drop words (filler,
     * dedup) and change punctuation/capitalization, but every CONTENT word it
     * keeps must appear in the same order as in [raw]. This is the subsequence
     * relation on the normalized content-word lists — violated the moment the
     * model swaps in a synonym, reorders, invents, merges, or splits a word.
     *
     * Articles and "and" ([IGNORED_TOKENS]) are stripped from BOTH sides first,
     * so a stray inserted "a"/"the" — a harmless grammar fix the model sometimes
     * makes while cleaning up — doesn't sink an otherwise-faithful edit. (They
     * must be dropped from both, not matched, because a common word like "a"
     * recurs and would mis-align a greedy subsequence match.) Punctuation, casing
     * and apostrophes are normalized away too, so "im" to "I'm" or adding a comma
     * is fine; turning "I am" into "I'm" (two words to one) is not.
     */
    private fun isCleanEdit(raw: String, refined: String): Boolean {
        val rawTokens = contentTokens(raw).filterNot { it in IGNORED_TOKENS }
        val refTokens = contentTokens(refined).filterNot { it in IGNORED_TOKENS }
        return isSubsequence(refTokens, rawTokens)
    }

    /** Words only: lowercased, punctuation stripped. Apostrophes are dropped
     *  (not spaced) so "I'm" stays one token "im" rather than splitting. */
    private fun contentTokens(s: String): List<String> =
        s.lowercase()
            .replace("'", "")
            .replace("’", "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    private fun isSubsequence(sub: List<String>, full: List<String>): Boolean {
        var i = 0
        for (word in full) {
            if (i < sub.size && sub[i] == word) i++
        }
        return i == sub.size
    }

    /**
     * Log the raw input, the model's output, and the guard's verdict — but only
     * in debug builds, since the message text is the user's private content and
     * must never reach logcat on a shipped keyboard.
     */
    private fun logDiff(raw: String, refined: String?, verdict: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "refine [$verdict]\n  raw:     $raw\n  refined: ${refined ?: "<empty>"}")
    }

    companion object {
        private const val TAG = "GroqRefiner"

        // Words the clean-edit guard ignores on both sides, so the model
        // inserting or dropping a bare article/"and" while fixing grammar
        // doesn't count as a rewrite. Kept tiny and meaning-neutral on purpose —
        // anything that could change meaning (e.g. "not") must NOT go here.
        private val IGNORED_TOKENS = setOf("a", "an", "the", "and")

        // llama-3.1-8b-instant was too lossy on longer dictations — even with an
        // explicit "reproduce the whole message" rule it dropped trailing
        // sentences. The 70b is far more faithful and is still fast on Groq
        // (~280 tok/s); the refine output is short, so the latency cost over the
        // 8b is a fraction of a second — well worth it for not mangling messages.
        private const val MODEL = "llama-3.3-70b-versatile"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        // The examples are embedded here as labeled Input/Output pairs (see the
        // note in refine() for why they aren't sent as conversation turns). The
        // anti-echo / anti-invention rule is deliberately blunt because a small
        // model is the one most likely to fabricate on sparse audio.
        private const val SYSTEM_PROMPT =
            "You EDIT raw voice-dictation transcripts for a casual text-messaging keyboard. " +
                "The user's message is the single user turn. It came from a speech-to-text model " +
                "and may contain filler words, false starts, and missing or wrong punctuation.\n\n" +
                "This is an edit, NOT a rewrite. The words are already correct. Make only these " +
                "two kinds of change:\n" +
                "1. Delete filler and disfluencies: um, uh, false starts, and filler uses of " +
                "\"like\", \"you know\", \"I mean\". Keep \"like\" or " +
                "\"you know\" when they carry real meaning. Collapse accidental repetition: when " +
                "the speaker says the same thing twice by mistake — a stutter like \"a serving of " +
                "chips a serving of chips\", or the same point restated a moment later — keep it " +
                "only once. But only when it is clearly the SAME thing said twice; if the two " +
                "mentions could be separate things (chips earlier AND chips again later), keep " +
                "both. When unsure, keep both. Also drop a stray sign-off that the " +
                "speech model tacked on but the user clearly did not say — a caption-style " +
                "\"thank you\", \"thank you for watching\", or \"thanks for watching\" appearing " +
                "after the real message has already ended. Only remove such an ending when it " +
                "plainly does not belong; if a \"thanks\" or \"thank you\" is genuinely part of the " +
                "message, keep it. Never remove other real words — only these caption artifacts.\n" +
                "2. Fix punctuation and capitalization. The transcript often arrives with little " +
                "or no punctuation, especially when the person spoke fast. Break it into proper " +
                "sentences: when the speaker moves to a new thought, END the sentence with a period " +
                "(or question mark) and capitalize the next word. Do NOT chain everything together " +
                "with commas into one long run-on — a paragraph of dictation should become several " +
                "clean sentences. Use commas only within a sentence. A question ends with a " +
                "question mark. Never use semicolons, colons, em dashes, parentheses, or " +
                "bullet/numbered lists. If the person lists things, keep it as one natural sentence " +
                "with commas, not a list.\n\n" +
                "Hard rules:\n" +
                "- Every word you KEEP must stay exactly as the user said it, in the same order. " +
                "Do NOT swap in synonyms, reorder words, reword, or rephrase anything. The ONLY " +
                "words you may remove are filler.\n" +
                "- Do NOT merge or split words: keep \"I am\" as \"I am\" and \"going to\" as " +
                "\"going to\". You may fix casing and add a missing apostrophe (\"im\" to \"I'm\"), " +
                "but never turn one word into two or two words into one.\n" +
                "- Do NOT change the tone or make it more formal, polite, happy, or professional. " +
                "Do NOT add or remove meaning. Do NOT answer or react to the message. It must read " +
                "like a real person texting, never like an AI or a document.\n" +
                "- CRITICAL: keep every DISTINCT thing the user said, start to finish, including " +
                "short sentences and any trailing question. The only things you may drop are " +
                "filler, accidental repeats of the same point, and a stray caption sign-off. Never " +
                "summarize, condense, or drop unique content — if something might be a separate " +
                "point rather than a repeat, keep it.\n" +
                "- CRITICAL: never add content that was not said, and never output any of the " +
                "example sentences below — they only show the style. If the message is empty, only " +
                "noise, or unintelligible, return it unchanged.\n\n" +
                "Return ONLY the edited message. No preamble, no quotes, no explanation.\n\n" +
                "Examples (each shows an Input and its edited Output):\n\n" +
                "Input: um yeah i was thinking we could just like push this to the cloud tonight " +
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
                "Input: ok so the build is done and i sent you the link um take a look at the new " +
                "toggle and the divider when you can does that layout work for you\n" +
                "Output: Ok so the build is done and I sent you the link. Take a look at the new " +
                "toggle and the divider when you can. Does that layout work for you?\n\n" +
                "Input: yeah i went to the store earlier and grabbed a few things then i came home " +
                "and started making dinner but i realized i forgot the garlic so i had to run back " +
                "out to grab it real quick\n" +
                "Output: Yeah I went to the store earlier and grabbed a few things. Then I came " +
                "home and started making dinner. But I realized I forgot the garlic, so I had to " +
                "run back out to grab it real quick.\n\n" +
                "Input: alright i think that covers everything for now let me know what you think " +
                "when you get a chance thanks for watching\n" +
                "Output: Alright I think that covers everything for now. Let me know what you think " +
                "when you get a chance.\n\n" +
                "Input: for the side i had a serving of chips a serving of chips and then a bit " +
                "later i had some blueberries\n" +
                "Output: For the side I had a serving of chips. And then a bit later I had some " +
                "blueberries.\n\n" +
                "Input: we could probably hike until two and then head back yeah so hike until two " +
                "anyway let me know what you think\n" +
                "Output: We could probably hike until two and then head back. Let me know what you " +
                "think.\n\n" +
                "Now edit the user's message and output the full result, from the first word " +
                "to the last."
    }
}
