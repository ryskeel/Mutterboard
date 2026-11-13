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
 * Same job, same model, same guards, and the SAME PROMPT as [GroqRefiner] with
 * one rule added: the message does not end with a period, because a text
 * message doesn't. Every other sentence break, comma and capital is the default
 * pass's, so casual output reads exactly like default output minus that one
 * period.
 *
 * Two earlier versions went further, rendering the edit in the user's own
 * texting hand: fewer commas, places and brands lowercased, examples taken from
 * their typed messages. Both read worse than the default pass - the first like
 * early-2010s voice dictation, the second as run-on sentences. See the note
 * above [CASUAL_SYSTEM_PROMPT] before growing this prompt again.
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
            // Groq admits a request only if its EXPECTED output fits the
            // tier's output-tokens-per-minute budget. With no max_tokens it
            // assumes the model default (2048), over the on_demand limit of
            // 1000, so every refine was rejected 429 and silently fell back to
            // the raw transcript. This pass rewrites its input, so output
            // length tracks input length: budget from the transcript instead of
            // pinning a constant, which would either truncate a long dictation
            // or reserve the whole minute for one request.
            put("max_tokens", maxTokensFor(text))
            put("messages", messages)
        }
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Silent at the product level by design - the caller commits the
                // raw transcript so a message is never lost. Say WHY in debug
                // builds: that silence is what hid a dead model id for days.
                if (BuildConfig.DEBUG) Log.i(TAG, "refine transport failure: $e")
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "refine HTTP ${response.code}: ${body?.take(500)}")
                    }
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
        // faithfulness the 27b already demonstrated. Moved off qwen3.6-27b when
        // Groq stopped serving it - see the note in GroqRefiner, and keep the
        // two ids in step: a stale id here breaks only casual mode, which reads
        // as the toggle doing nothing rather than as an outage.
        private const val MODEL = "qwen/qwen3.8-27b"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Output-token budget for refining [text]. The refined message is at
         * most about as long as the raw one, so estimate from the transcript
         * (~4 chars/token), double it for headroom, and clamp at both ends:
         * never so small that a long dictation is truncated mid-sentence, never
         * so large that one request reserves the tier's whole per-minute budget.
         */
        fun maxTokensFor(text: String): Int =
            ((text.length / 4) * 2 + 64).coerceIn(128, 900)

        // THIS PROMPT IS GroqRefiner.SYSTEM_PROMPT PLUS ONE RULE. Everything
        // above "ONE MORE CHANGE" is the default prompt verbatim - the opening,
        // all three numbered rules - and so is the hard-rules block after it. The
        // examples are the default's own with their final periods taken off, plus
        // one arriving already punctuated in place of a bare one, and one more
        // that is new. If the default prompt is edited, edit the carried-over text
        // here to match. Casual mode is the default edit without the period at the
        // end, which is what a text message looks like, and nothing else.
        //
        // That is the user's call (2026-11-13), after weeks of casual output
        // arriving as one unbroken run-on: forty-odd words of connected speech
        // with no sentence break, which they described as reading like cheap
        // voice dictation. The version it replaced rendered the edit in the
        // user's "own texting hand": five extra bullets and fifteen examples,
        // most of them messages the user had typed. Three of those bullets were
        // comma DELETIONS - after a one-word opener, after a short leading
        // clause, and before "but", "and", "so" joining two clauses - and the
        // typed examples were long sentences carried through unbroken. Connected
        // speech ("and then you can... so I've been... and so it's...") is the
        // shape Whisper can punctuate with commas before "and" and "so"
        // rather than with periods, so the deletion rules could strip the only
        // punctuation it had, leaving rule 2 nothing to keep. The lowercased
        // places and brands and the exclamation marks the examples taught went
        // with them. Not proven: that the move to qwen3.8-27b in September made
        // the model apply the deletions more literally. The physical keyboard
        // also has no mode toggle, so anyone in Casual when they moved to it
        // stayed there.
        //
        // Rule 1 is the default's again too. The casual copy had grown a
        // false-start definition and a filler-"like" test, each fixing a failure
        // the casual delta itself caused: a comma-splice bullet that sheltered a
        // false start, and typed examples that kept "like" twice for every once
        // they deleted it. With both causes gone, the default's rule 1 is the one
        // with the track record. If casual mode now gets something wrong that the
        // default pass gets right on the same transcript, the cause can only be
        // the one rule below or the examples.
        //
        // The rule says outright that dropping the final period is not a reason
        // to merge sentences, because merging is the failure it replaced. The
        // two non-default examples are there because an example only teaches
        // when its Input and Output disagree about the thing being tested: every
        // default example arrives bare, with no final period to delete, so on
        // their own they never show a period being removed or a run-on being
        // broken up while one is. The running-late example now differs from its
        // Input by the final period alone; the day-out example is a run-on chained
        // with commas, the shape that went wrong, broken back into sentences.
        //
        // NEVER add an example taken from dictated output, including output this
        // refiner produced. One sample once turned out to have been dictated
        // rather than typed, and its unpunctuated tail, a transcription artifact,
        // was read as a habit. Synthetic examples or the user's own typing only.
        //
        // The retired bullets and the typed examples are in git history before
        // 2026-11-13, with a long comment explaining the failure behind each one.
        // Read it before bringing any of them back, and bring them back one at a
        // time: length in this prompt is spent out of rule 1's budget.
        private const val CASUAL_SYSTEM_PROMPT =
            "You EDIT raw voice-dictation transcripts for a casual text-messaging keyboard. " +
            "The user's message is the single user turn. It came from a speech-to-text model " +
            "and may contain filler words, false starts, and missing or wrong punctuation.\n\n" +
            "This is an edit, NOT a rewrite. The words are already correct. Make only these " +
            "three kinds of change:\n" +
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
            "with commas, not a list.\n" +
            "3. Carry out a spelling instruction, then delete it. Sometimes the user spells a "  +
            "word out letter by letter because they know the speech-to-text model will get it " +
            "wrong — \"the Townsend Y, like spelled W-Y-E\", \"my friend Kaitlyn, that's " +
            "K-A-I-T-L-Y-N\". Those letters are an instruction addressed to YOU, not part of " +
            "the message. THE LETTERS ARE AUTHORITATIVE: when they disagree with how the " +
            "speech-to-text model spelled the word — even by a single letter, even when its " +
            "spelling looks more correct or more standard to you — THE LETTERS WIN. " +
            "\"the Mexican place is Las Fuentes, spelled L-A-S-F-U-E-N-T-A-S\" becomes " +
            "\"the Mexican place is Las Fuentas\", NOT \"Las Fuentes\". Spell the word the " +
            "way the letters say, put it in place of the " +
            "model's earlier attempt at that word, and delete the letters and their lead-in " +
            "(\"like spelled\", \"that's spelled\", \"spelled\", \"as in\") entirely. So " +
            "\"this thing called the Townsend Y, like spelled W-Y-E\" becomes \"this thing " +
            "called the Townsend Wye\". The word must appear EXACTLY ONCE in your output — " +
            "NEVER leave both the model's attempt and the spelled-out version. When the " +
            "spelling instruction is the sentence's whole predicate, delete the whole " +
            "predicate: \"Oh yeah, Las Fuentas is spelled L-A-S-F-U-E-N-T-A-S\" becomes " +
            "\"Oh yeah, Las Fuentas\", never \"Las Fuentas is spelled Las Fuentas\". " +
            "Do this ONLY when the letters clearly spell out a word " +
            "the user just said or is introducing. Leave a genuine initialism, acronym or code " +
            "alone — \"send me the PDF\", \"he works at IBM\", \"my confirmation is A-4-7-J\" " +
            "all stay exactly as said.\n\n" +

            // The delta. Everything above this point is GroqRefiner's prompt.
            "ONE MORE CHANGE, and the only way this edit differs from a normal one: the " +
            "message must NOT end with a period. Do 1, 2 and 3 exactly as written, then " +
            "delete the period at the very end of the message, so the last sentence simply " +
            "stops. Every other period stays exactly where rule 2 put it: a message with " +
            "several thoughts is still several sentences, each closed with a period except " +
            "the last. Dropping the final period is NOT a reason to drop any other " +
            "punctuation or to join sentences together. A final \"?\" or \"!\" stays, and a " +
            "question still always ends with a question mark.\n\n" +

            "Hard rules:\n" +
            "- Every word you KEEP must stay exactly as the user said it, in the same order, " +
            "with ONE exception: a word the user spelled out letter by letter is respelled to " +
            "match those letters, per rule 3. That exception is the only way a kept word may " +
            "change. " +
            "Do NOT swap in synonyms, reorder words, reword, or rephrase anything. The ONLY " +
            "words you may remove are filler, and the letters of a spelling instruction you have " +
            "already carried out under rule 3.\n" +
            "- Do NOT merge or split words: keep \"I am\" as \"I am\" and \"going to\" as " +
            "\"going to\". You may fix casing, add a missing apostrophe (\"im\" to \"I'm\"), " +
            "and respell a word the user spelled out under rule 3 (\"Katie\" to \"Katy\" when " +
            "they said K-A-T-Y), " +
            "but never turn one word into two or two words into one.\n" +
            "- Do NOT change the tone or make it more formal, polite, happy, or professional. " +
            "Do NOT add or remove meaning. It must read like a real person texting, never " +
            "like an AI or a document.\n" +
            "- CRITICAL: NEVER answer, reply to, or react to the message. The user is often " +
            "dictating a question to send to SOMEONE ELSE. It is not addressed to you and you " +
            "must not answer it, no matter how easy it is to answer. Output the cleaned-up " +
            "question itself, nothing else.\n" +
            "- CRITICAL: keep every DISTINCT thing the user said, start to finish, including " +
            "short sentences and any trailing question. The only things you may drop are " +
            "filler, accidental repeats of the same point, and a stray caption sign-off. Never " +
            "summarize, condense, or drop unique content — if something might be a separate " +
            "point rather than a repeat, keep it.\n" +
            "- CRITICAL: never add content that was not said — re-spelling a word the user spelled " +
            "out for you is NOT adding content — and never output any of the " +
            "example sentences below — they only show the style. If the message is empty, only " +
            "noise, or unintelligible, return it unchanged.\n\n" +
            "Return ONLY the edited message. No preamble, no quotes, no explanation.\n\n" +

            "Examples (each shows an Input and its edited Output). Some Inputs arrive bare, " +
            "the way a fast-spoken transcript does; others arrive already punctuated by the " +
            "speech-to-text model, which is the usual case. Either way the Output is broken " +
            "into proper sentences, and the only period missing is the last one:\n\n" +

            "Input: um yeah i was thinking we could just like push this to the cloud tonight " +
            "and uh see if it actually works\n" +
            "Output: Yeah I was thinking we could just push this to the cloud tonight and see " +
            "if it actually works\n\n" +

            "Input: can you grab a few things from the store milk eggs bread and uh maybe some " +
            "coffee too\n" +
            "Output: Can you grab a few things from the store. Milk, eggs, bread, and maybe " +
            "some coffee too\n\n" +

            "Input: um how do you get to the airport from downtown like whats the fastest way\n" +
            "Output: How do you get to the airport from downtown? What's the fastest way?\n\n" +

            "Input: I'm running like ten minutes late, sorry. I got stuck behind this super " +
            "slow truck on the highway.\n" +
            "Output: I'm running like ten minutes late, sorry. I got stuck behind this super " +
            "slow truck on the highway\n\n" +

            "Input: ok so the build is done and i sent you the link um take a look at the new " +
            "toggle and the divider when you can does that layout work for you\n" +
            "Output: Ok so the build is done and I sent you the link. Take a look at the new " +
            "toggle and the divider when you can. Does that layout work for you?\n\n" +

            "Input: yeah i went to the store earlier and grabbed a few things then i came home " +
            "and started making dinner but i realized i forgot the garlic so i had to run back " +
            "out to grab it real quick\n" +
            "Output: Yeah I went to the store earlier and grabbed a few things. Then I came " +
            "home and started making dinner. But I realized I forgot the garlic, so I had to " +
            "run back out to grab it real quick\n\n" +

            "Input: Yeah, so we got there around noon, and it was already packed, so we just " +
            "walked around for a while, and then we found this little taco place, and " +
            "honestly that was the best part of the whole day.\n" +
            "Output: Yeah, so we got there around noon, and it was already packed. So we just " +
            "walked around for a while. And then we found this little taco place, and " +
            "honestly that was the best part of the whole day\n\n" +

            "Input: alright i think that covers everything for now let me know what you think " +
            "when you get a chance thanks for watching\n" +
            "Output: Alright I think that covers everything for now. Let me know what you think " +
            "when you get a chance\n\n" +

            "Input: for the side i had a serving of chips a serving of chips and then a bit " +
            "later i had some blueberries\n" +
            "Output: For the side I had a serving of chips. And then a bit later I had some " +
            "blueberries\n\n" +

            "Input: we went out to this place called the townsend y like spelled w y e and it was " +
            "packed but honestly still worth it\n" +
            "Output: We went out to this place called the Townsend Wye. It was packed but " +
            "honestly still worth it\n\n" +

            "Input: we could probably hike until two and then head back yeah so hike until two " +
            "anyway let me know what you think\n" +
            "Output: We could probably hike until two and then head back. Let me know what you " +
            "think\n\n" +

            "Now edit the user's message and output the full result, from the first word " +
            "to the last."
    }
}
