package com.example.mutterboard

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * The casual-mode refine pass: the alternative to [GroqRefiner], selected by
 * [RefineMode.CASUAL] from the keyboard.
 *
 * Same job, same model, same guards — the ONLY thing that differs is rule 2 of
 * the system prompt. [GroqRefiner] fixes punctuation and capitalization to be
 * *correct*; this one applies the user's own texting orthography instead: no
 * terminal period, run-ons left alone, the occasional lowercase sentence start,
 * no comma before "but"/"and"/"so". The word-level discipline that preserves the
 * user's tone ("every word you keep stays exactly as the user said it, no
 * synonyms, no reordering") is carried over verbatim, because that rule is what
 * makes the default pass good and casual mode is meant to be just as good — the
 * same edit in a different hand, not a looser cousin. Its few-shot outputs are
 * lightly adapted from the user's own real text messages.
 *
 * This class deliberately DUPLICATES the guard helpers rather than sharing them
 * with [GroqRefiner]. The isolation is the point: the default path is the one the
 * user relies on daily, and deleting this file plus the toggle wiring must
 * restore today's behavior exactly. See [GroqRefiner] for the full rationale
 * behind the two checks — [isInvented] hard-gates, [isCleanEdit] only labels the
 * debug log.
 */
class CasualRefiner(private val apiKey: String) {

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
        // turns) and the transcript is the ONLY user turn — see the note in
        // GroqRefiner.refine() for the echo failure this avoids.
        val messages = JSONArray().apply {
            put(message("system", CASUAL_SYSTEM_PROMPT))
            put(message("user", text))
        }
        val payload = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0)
            put("reasoning_effort", "none")
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
                val invented = refined != null && isInvented(text, refined)
                if (BuildConfig.DEBUG) {
                    val verdict = when {
                        refined == null -> "empty"
                        invented -> "invented"
                        isCleanEdit(text, refined) -> "clean-edit"
                        else -> "rewrite"
                    }
                    logDiff(text, refined, verdict)
                }
                onResult(if (invented) null else refined)
            }
        })
    }

    fun close() {
        // Network I/O, and close() is called from the IME's main thread — see
        // GroqRefiner.close().
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
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .trim()
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
     * True if [refined] is substantially built from words that never appeared in
     * [raw] — the signature of the model ANSWERING the message or fabricating,
     * rather than editing. Identical to [GroqRefiner.isInvented], and it needs no
     * casual-specific loosening: [contentTokens] already lowercases and strips
     * punctuation and apostrophes, so dropping a terminal period or leaving a
     * sentence lowercase is invisible to it. Casual mode gets the same
     * anti-answer safety net for free.
     *
     * Internal (not private) only so the unit test can pin the regression.
     */
    internal fun isInvented(raw: String, refined: String): Boolean {
        val rawSet = contentTokens(raw).toSet()
        val refTokens = contentTokens(refined).filterNot { it in IGNORED_TOKENS }
        if (refTokens.isEmpty()) return false
        val novel = refTokens.count { it !in rawSet }
        return novel > maxOf(3, refTokens.size / 4)
    }

    /** Advisory only: labels the debug log. See [GroqRefiner]. */
    private fun isCleanEdit(raw: String, refined: String): Boolean {
        val rawTokens = contentTokens(raw).filterNot { it in IGNORED_TOKENS }
        val refTokens = contentTokens(refined).filterNot { it in IGNORED_TOKENS }
        return isSubsequence(refTokens, rawTokens)
    }

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

    /** Debug builds only — the message text is the user's private content. */
    private fun logDiff(raw: String, refined: String?, verdict: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "refine [$verdict]\n  raw:     $raw\n  refined: ${refined ?: "<empty>"}")
    }

    companion object {
        private const val TAG = "CasualRefiner"

        private val IGNORED_TOKENS = setOf("a", "an", "the", "and")

        // Same model as the default pass, on purpose: casual mode is the same
        // edit rendered differently, so there's no reason to trade away the
        // faithfulness the 27b already demonstrated.
        private const val MODEL = "qwen/qwen3.6-27b"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        // Rule 1 and every hard rule below are carried over from
        // GroqRefiner.SYSTEM_PROMPT unchanged. Rule 2 is the whole difference.
        //
        // The rules in section 2 and the Output side of every example were
        // derived from a sample of the user's own real, hand-TYPED text messages,
        // not from a generic idea of "casual" — the absent terminal period, the
        // occasional lowercase sentence start after a period, the missing comma
        // before "but"/"and"/"so", and the comma splices are all measured habits,
        // which is why they're stated as hard rules rather than as a vague
        // instruction to be informal. A model told to "write casually" reaches for
        // slang and emoji; told these rules, it just stops typesetting.
        //
        // One sample had to be thrown out of that corpus, and the lesson is worth
        // keeping: it was itself dictated rather than typed, and its unpunctuated
        // tail was a transcription artifact, not a habit. Taking it at face value
        // produced a rule telling the model that long stretches with NO
        // punctuation were correct here — which would have made casual mode a
        // worse transcript rather than a truer voice. So separate thoughts always
        // get a period; only what happens inside a sentence is loose. Any future
        // sample added here must be one the user actually typed.
        private const val CASUAL_SYSTEM_PROMPT =
            "You EDIT raw voice-dictation transcripts into text messages, written in this " +
                "specific person's own casual texting hand. The user's message is the single " +
                "user turn. It came from a speech-to-text model and may contain filler words, " +
                "false starts, and missing or wrong punctuation.\n\n" +
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
                "2. Punctuate and capitalize it the way THIS person texts. They do not write like " +
                "a document, and the goal is that a recipient would never guess this was " +
                "dictated. Follow these rules exactly:\n" +
                "   - NEVER end the whole message with a period. The last sentence simply stops. " +
                "It may end with a question mark if it is a real question, or an exclamation mark " +
                "if it is genuinely excited, but never a period.\n" +
                "   - INSIDE the message, separate thoughts DO get a period between them. Always. " +
                "Never run two separate thoughts together with no punctuation at all — that reads " +
                "as a bad transcript, not as a person texting.\n" +
                "   - What is casual is what happens WITHIN a sentence. A single sentence may run " +
                "long with almost no internal punctuation, its clauses strung together with " +
                "\"and\", \"but\", or \"so\" and no comma before them. Do not chop such a sentence " +
                "up into short correct ones.\n" +
                "   - Capitalize the first word of the message, and most sentences after a " +
                "period. But leave roughly one sentence in three starting with a LOWERCASE " +
                "letter, especially short ones and ones beginning with lol, haha, ok, yeah, it's, " +
                "or let me know.\n" +
                "   - Always capitalize \"I\".\n" +
                "   - Commas: use them after a lead-in word or phrase (\"Ok,\" \"Yeah,\" \"Lol " +
                "wtf,\" \"For what it's worth,\") and at a genuine pause. Joining two short " +
                "clauses with just a comma is fine and normal. NEVER put a comma before \"but\", " +
                "\"and\", or \"so\".\n" +
                "   - Write numbers as digits: 3, 20, 3-4, August 25th. Never spell them out.\n" +
                "   - Parentheses are fine for a short aside, and a spaced hyphen \" - \" may be " +
                "used once for a break in thought. Never use semicolons, colons, em dashes, or " +
                "bullet/numbered lists. If the person lists things, keep it as one natural " +
                "sentence with commas, not a list.\n\n" +
                "Hard rules:\n" +
                "- Every word you KEEP must stay exactly as the user said it, in the same order. " +
                "Do NOT swap in synonyms, reorder words, reword, or rephrase anything. The ONLY " +
                "words you may remove are filler.\n" +
                "- Casing, apostrophes and number formatting are the only word-level changes you " +
                "may make. Do NOT merge or split words: keep \"I am\" as \"I am\". Keep casual " +
                "spoken forms EXACTLY as said — \"gonna\", \"wanna\", \"kinda\", \"y'all\", " +
                "\"cause\" stay as they are and are never expanded to \"going to\", \"want to\", " +
                "\"you all\".\n" +
                "- Do NOT stretch, respell, or restyle words for effect. If the user said " +
                "\"yeah\", write \"yeah\", not \"yeahhh\". Never add \"lol\", \"haha\", or an " +
                "emoji that the user did not actually say. Casual means lighter punctuation, NOT " +
                "added slang or added personality.\n" +
                "- Do NOT change the tone or make it more formal, polite, happy, or professional, " +
                "and do NOT make it sloppier than these rules say. Do NOT add or remove meaning.\n" +
                "- CRITICAL: NEVER answer, reply to, or react to the message. The user is often " +
                "dictating a question to send to SOMEONE ELSE. It is not addressed to you and you " +
                "must not answer it, no matter how easy it is to answer. Output the cleaned-up " +
                "question itself, nothing else.\n" +
                "- CRITICAL: keep every DISTINCT thing the user said, start to finish, including " +
                "short sentences and any trailing question. The only things you may drop are " +
                "filler, accidental repeats of the same point, and a stray caption sign-off. Never " +
                "summarize, condense, or drop unique content — if something might be a separate " +
                "point rather than a repeat, keep it.\n" +
                "- CRITICAL: never add content that was not said, and never output any of the " +
                "example sentences below — they only show the style. If the message is empty, only " +
                "noise, or unintelligible, return it unchanged.\n\n" +
                "Return ONLY the edited message. No preamble, no quotes, no explanation.\n\n" +
                "Examples (each shows an Input and its edited Output). Study how the Outputs " +
                "punctuate — no final period, mixed sentence starts, sentences allowed to run long " +
                "internally but always closed off before the next thought:\n\n" +
                "Input: um im sure you already know this but the way to make it so you can like " +
                "fully customize the outer display and add whatever apps you want to it is with a " +
                "third party app called good lock\n" +
                "Output: I'm sure you already know this but the way to make it so you can fully " +
                "customize the outer display and add whatever apps you want to it is with a third " +
                "party app called Good Lock\n\n" +
                "Input: uh i put it on my calendar to sign up on august twenty fifth when the " +
                "tickets go on sale let me know if you do the same\n" +
                "Output: I put it on my calendar to sign up on August 25th when the tickets go on " +
                "sale. let me know if you do the same!\n\n" +
                "Input: i really dont blame you at all its seriously ok um if theyre sold out now " +
                "its likely they were already sold out when we were there a few weeks ago and we " +
                "just didnt know it\n" +
                "Output: I really don't blame you at all, it's seriously ok. If they're sold out " +
                "now it's likely they were already sold out when we were there a few weeks ago " +
                "and we just didn't know it\n\n" +
                "Input: lol good call ok in that case i think its pretty fun and also surprisingly " +
                "economical to go with the chefs choice experience assuming they still have it\n" +
                "Output: lol good call. Ok, in that case I think it's pretty fun and also " +
                "surprisingly economical to go with the chef's choice experience (assuming they " +
                "still have it)\n\n" +
                "Input: yeah i was playing basically as a ranger and had like super strong " +
                "dexterity and bow skills um this time im going more of a battle mage with high " +
                "strength and high intelligence yeah it already feels way different like in my " +
                "last run i could get hit maybe twice before dying this time im much more tanky\n" +
                "Output: Yeah I was playing basically as a ranger and had super strong dexterity " +
                "and bow skills. This time I'm going more of a battle mage with high strength and " +
                "high intelligence. Yeah it already feels way different. Like in my last run I " +
                "could get hit maybe twice before dying, this time I'm much more tanky\n\n" +
                "Input: never too late to make friends although i know its way easier said than " +
                "done um for what its worth i havent made many close friends since we moved to " +
                "knoxville and i have to remind myself that i do have plenty of friends even if " +
                "theyre not in the same city same goes for you\n" +
                "Output: Never too late to make friends, although I know it's way easier said than " +
                "done. For what it's worth, I haven't made many close friends since we moved to " +
                "Knoxville and I have to remind myself that I do have plenty of friends, even if " +
                "they're not in the same city. Same goes for you\n\n" +
                "Input: um hey do you know what time yall are heading out tomorrow like are we " +
                "still doing the early thing\n" +
                "Output: Hey do you know what time y'all are heading out tomorrow? like are we " +
                "still doing the early thing?\n\n" +
                "Input: i didnt mean to be condescending by calling motorola phones budget by the " +
                "way i loved that phone i just mean motorola is known for focusing a lot on " +
                "packing it a lot of cool features and making cool hardware but then also known " +
                "for waiting like three months to push major software updates and stuff\n" +
                "Output: I didn't mean to be condescending by calling motorola phones budget by " +
                "the way. I loved that phone. I just mean motorola is known for focusing a lot on " +
                "packing it a lot of cool features and making cool hardware but then also known " +
                "for waiting like 3 months to push major software updates and stuff\n\n" +
                "Now edit the user's message and output the full result, from the first word " +
                "to the last."
    }
}
