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
 * Same job, same model, same guards, and the SAME PROMPT as [GroqRefiner] with a
 * short delta appended. [GroqRefiner] fixes punctuation and capitalization to be
 * *correct*; this one does all of that and then renders the result in the user's
 * own texting hand: no period on the final sentence, fewer commas, places and
 * brands left lowercase, a stretched word carried through untouched. The
 * word-level discipline that preserves the user's tone ("every word you keep
 * stays exactly as the user said it, no synonyms, no reordering") is carried over
 * verbatim, because that rule is what makes the default pass good and casual mode
 * is meant to be just as good - the same edit in a different hand, not a looser
 * cousin. Its few-shot Outputs are the user's own hand-typed messages.
 *
 * An earlier version diverged much further, with a prompt two and a half times
 * the default's length organized as a two-pass typesetting procedure. It read
 * like early-2010s voice dictation and it stopped deleting filler. See the note
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

        /**
         * Output-token budget for refining [text]. The refined message is at
         * most about as long as the raw one, so estimate from the transcript
         * (~4 chars/token), double it for headroom, and clamp at both ends:
         * never so small that a long dictation is truncated mid-sentence, never
         * so large that one request reserves the tier's whole per-minute budget.
         */
        fun maxTokensFor(text: String): Int =
            ((text.length / 4) * 2 + 64).coerceIn(128, 900)

        // THIS PROMPT IS GroqRefiner.SYSTEM_PROMPT PLUS A SHORT DELTA. Keep it
        // that way. The opening paragraph, both numbered rules and the entire
        // hard-rules block are carried over verbatim; the only additions are the
        // five CASUAL HAND bullets and the examples. If the default prompt is
        // ever edited, edit the carried-over text here to match rather than
        // letting the two drift.
        //
        // That structure is a deliberate revert. An earlier version of this file
        // grew a prompt roughly two and a half times the length of the default's,
        // organized as an explicit two-pass procedure (split into sentences, then
        // strip typesetting) with about 130 lines of comma law hanging off it.
        // Every rule in it had been justified by a real observed failure, and the
        // result was still worse: the user's verdict after a morning of live use
        // was that it read like 2012-era voice dictation. Two things went wrong.
        // Rule 1, delete filler, is the SAME SENTENCE in both files, but buried
        // under that much punctuation machinery it stopped firing, and "um" and
        // "uh" started surviving into sent messages. And reframing the job as a
        // typesetting procedure lost the thing that makes the default pass good:
        // it is an EDIT, described as an edit, with the word-level discipline
        // stated up front. Casual mode is the same edit in a different hand, so
        // its prompt has to be the same prompt with a different hand appended.
        // Length here is not free - it is spent out of rule 1's budget.
        //
        // The five bullets are the user's own hard rules, given after testing.
        // Two of them reversed rules the long version had:
        //
        //   - Commas before "but"/"and"/"so" joining two clauses are DELETED.
        //     The long version had gone back and forth on this; the user's typed
        //     corpus deletes that comma in all three places it occurs, and they
        //     confirmed the corpus over their own first description of the rule.
        //   - Sentence starts are NO LONGER lowercased. The long version
        //     lowercased sentences beginning "and", "so", "lol", "wait" and
        //     others. That rule is gone entirely, and the examples below are
        //     re-rendered with those capitals restored.
        //
        // The expanded filler-"like" test in rule 1 is the second divergence from
        // GroqRefiner's carried-over text, and it is there because casual mode was
        // measurably worse at the SAME rule: given a transcript with three filler
        // "like"s it deleted none, while default deleted its one cleanly from an
        // identical rule 1. The suspected cause is example balance, not wording -
        // default's examples delete filler "like" twice and keep it once, while
        // this file's inherited corpus keeps it twice ("going like 20 miles per
        // hour", "Like in my last run") and deletes it once. Both keeps are
        // correct, so the fix is not to remove them; it is to give rule 1 a test
        // the model can apply instead of the word "filler". If "like" starts
        // surviving again, add a DELETING example rather than more adjectives -
        // and get its Output from the user's own typing, per the note below.
        //
        // The false-start definition and the exclusion on the comma-splice bullet
        // are one fix for one observed failure, and they are the only place this
        // prompt's carried-over text diverges from GroqRefiner's. A live dictation
        // came back as "but it was, the water was like exceptionally cold": rule 1
        // lists "false starts" without ever defining one, while the comma-splice
        // bullet names that exact shape - two short clauses, no conjunction - and
        // says KEEP. The specific rule beat the vague one and sheltered the very
        // thing rule 1 was supposed to delete. Adding the splice rule is what
        // created the bug, so the exclusion belongs on the splice rule, not only
        // in rule 1. GroqRefiner has the same undefined "false starts" and may
        // well have the same bug without the splice rule to blame; it is untested
        // there, and fixing it is its own change.
        //
        // The comma splice survives both cuts, and had to be named rather than
        // merely permitted. Measured against the user's typed messages this
        // refiner runs UNDER their comma rate, not over, and the whole deficit is
        // that one construction - two short clauses side by side with no
        // conjunction, which they write constantly.
        //
        // Lowercasing places, businesses, brands, days and months is the one
        // bullet that ADDS work rather than removing it, and it is also the one
        // that contradicts the typed corpus: the user capitalized "Knoxville",
        // "Good Lock", "Saturday" and "August 25th" in the messages this prompt
        // was built from. They chose the rule over the corpus knowingly, so the
        // affected Outputs below are re-rendered lowercase to keep the prompt
        // internally consistent. If output starts looking wrong here, this is the
        // first bullet to suspect, and the corpus is the evidence against it.
        // People's names are carved out because that distinction was explicit.
        //
        // Nationalities and languages are carved OUT of the lowercase rule. Left in,
        // it rendered "this Mexican restaurant" as "this mexican restaurant", which
        // the user judged wrong: a demonym reads as belonging with people's names
        // rather than with the store and city names the rule is aimed at.
        //
        // Whisper's output is ALREADY punctuated and capitalized - it returns
        // document-style prose - so most of what these bullets do is subtraction.
        // But on fast speech it returns bare unpunctuated text, which is why the
        // first four examples are bare and the rest are typeset. A prompt written
        // for only one of those shapes leaves the other nearly untouched, and
        // that has happened here in both directions.
        //
        // Emphasis is Whisper's to give, not this prompt's to guess. A held vowel
        // it transcribed literally ("Noooo") is free and must simply survive.
        // When it flattens one - it returned a plain "Dude" for a word the user
        // clearly stretched - that emphasis is gone, and no prompting recovers
        // from the text what the audio no longer carries. A rule licensing the
        // model to stretch a word whenever a message "sounds excited" was tried
        // and removed: it guesses from context rather than acoustics, so it fires
        // on messages the user never stretched. If this is worth chasing, the
        // lever is Whisper's own prompt field or word-level timestamps.
        //
        // Rule 3 is carried over from GroqRefiner verbatim, including the two hard
        // rules it had to be carved out of. It is the one rule in either prompt
        // that CHANGES a word, and it exists because the user talks TO the
        // transcriber mid-sentence: they say a name they know Whisper will mangle
        // and then spell it out. Before it, both halves survived into the sent
        // message. Its casual example also pins the interaction with the lowercase
        // rule - the respelled word is a place, so it comes out "townsend wye",
        // not "Townsend Wye". If that reads wrong, the lowercase bullet is the
        // thing to revisit, not rule 3.
        //
        // Two things are now carried by EXAMPLE ONLY, having previously had rules
        // of their own. Both were real observed failures, so if either regresses,
        // restoring the rule is justified - but each one costs rule 1 budget:
        //
        //   - "Yep, yep" must not collapse to "Yep". Default rule 1 says to
        //     collapse repetition, which is right for genuine stutters and wrong
        //     for a doubled short interjection, where the repeat is deliberate
        //     emphasis. The "Yep, yep" example is the only thing defending it.
        //   - The comma after "Ok" survives while other one-word openers lose
        //     theirs. That exception lives in the rule text but with no
        //     justification attached: it is simply what the corpus does.
        //
        // Every Output below is the user's own hand - messages they actually
        // typed, or ones they wrote out as what they wanted back - except the
        // first four, which are the default prompt's own synthetic examples with
        // the casual rules applied. NEVER add an example taken from dictated
        // output, including output this refiner produced. One sample once turned
        // out to have been dictated rather than typed, and its unpunctuated tail
        // was a transcription artifact rather than a habit; read at face value it
        // produced a rule blessing long unpunctuated stretches. If a new example
        // is needed, ask the user to type how they would have written it.
        private const val CASUAL_SYSTEM_PROMPT =
            "You EDIT raw voice-dictation transcripts for a casual text-messaging keyboard. " +
            "The user's message is the single user turn. It came from a speech-to-text model " +
            "and may contain filler words, false starts, and missing or wrong punctuation.\n\n" +

            "This is an edit, NOT a rewrite. The words are already correct. Make only these " +
            "three kinds of change:\n" +
            "1. Delete filler and disfluencies: um, uh, false starts, and filler uses of " +
            "\"like\", \"you know\", \"I mean\". A FALSE START is an abandoned run at a " +
            "phrase, immediately restarted: in \"that's insane Are you wait so you're telling " +
            "me\" the \"Are you\" is abandoned and restarted as \"wait so you're telling me\"; " +
            "in \"but it was, the water was exceptionally cold\" the \"it was,\" is abandoned " +
            "and restarted as \"the water was\". Delete the abandoned words and keep the " +
            "restart, INCLUDING any comma the speech model left after the abandoned fragment. " +
            "Filler \"like\" is by far the most common one this person says and the easiest " +
            "to miss, so apply this test to EVERY \"like\" in the message: if deleting it " +
            "leaves the meaning exactly the same, DELETE IT. \"in more like casual places\" " +
            "becomes \"in more casual places\", \"just like incredibly good\" becomes \"just " +
            "incredibly good\", \"the water was like exceptionally cold\" becomes \"the water " +
            "was exceptionally cold\", \"like you wouldn't even believe it\" becomes \"you " +
            "wouldn't even believe it\". Keep \"like\" or \"you know\" ONLY where it carries " +
            "real meaning - an approximation (\"like ten minutes late\", \"going like 20 miles " +
            "per hour\") or a genuine comparison. Collapse accidental repetition: when " +
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
            "3. Carry out a spelling instruction, then delete it. Sometimes the user spells a " +
            "word out letter by letter because they know the speech-to-text model will get it " +
            "wrong — \"the Townsend Y, like spelled W-Y-E\", \"my friend Kaitlyn, that's " +
            "K-A-I-T-L-Y-N\". Those letters are an instruction addressed to YOU, not part of " +
            "the message. THE LETTERS ARE AUTHORITATIVE: when they disagree with how the " +
            "speech-to-text model spelled the word — even by a single letter, even when its " +
            "spelling looks more correct or more standard to you — THE LETTERS WIN. " +
            "\"the Mexican place is Las Fuentes, spelled L-A-S-F-U-E-N-T-A-S\" becomes " +
            "\"the mexican place is las fuentas\", NOT \"las fuentes\". Spell the word the " +
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
            "CASUAL HAND: having done 1 and 2, now render the result the way THIS person " +
            "actually types on a phone. These five adjustments change punctuation and " +
            "capitalization ONLY — never a word, never the meaning, never the tone. Usually " +
            "the speech-to-text model has already typeset the message like a formal document, " +
            "and that typesetting is the machine's, not the user's, so most of the work here " +
            "is DELETION:\n" +
            "- NEVER end the whole message with a period. Delete the period the transcript " +
            "puts at the very end — the last sentence simply stops. Periods BETWEEN sentences " +
            "all stay exactly where rule 2 put them. This removes PERIODS only: a final \"?\" " +
            "or \"!\" stays, and a question STILL ALWAYS ends with a question mark even when " +
            "the transcript gave you no punctuation at all. A bare question reads as broken, " +
            "not as casual.\n" +
            "- DELETE these commas. After a one-word opener: \"Yeah, either way\" becomes " +
            "\"Yeah either way\", \"Dude, that's nuts\" becomes \"Dude that's nuts\", " +
            "\"Although, I'd be down for tacos\" becomes \"Although I'd be down for tacos\". " +
            "After a short leading clause: \"If we can go on Saturday, we'd\" becomes \"If we " +
            "can go on Saturday we'd\". And before \"but\", \"and\", \"so\" joining two " +
            "clauses: \"already sold out a few weeks ago, and we just didn't know it\" becomes " +
            "\"already sold out a few weeks ago and we just didn't know it\". KEEP the comma " +
            "after \"Ok\" or \"Okay\", and after a lead-in of two or more words (\"For what " +
            "it's worth,\"). KEEP the commas in a genuine list of things.\n" +
            "- KEEP a comma joining two short clauses that have NO conjunction between them: " +
            "\"I really don't blame you at all, it's seriously ok\", \"It's close, it's not " +
            "bad\", \"It's not a particular song, it's just a song I started making up\". This " +
            "person writes those constantly and flattening them makes the message read stiffer " +
            "than they actually write. But do NOT mistake a false start for one: in \"but it " +
            "was, the water was exceptionally cold\" the first fragment is abandoned, not a " +
            "clause, so rule 1 deletes it and its comma goes with it.\n" +
            "- LOWERCASE a proper noun naming a place, a business, a brand, a product, a day " +
            "or a month, even when the transcript capitalized it: \"Katie went to best buy " +
            "yesterday but couldn't find anything\". A PERSON's name always keeps its capital, " +
            "and so does a NATIONALITY or a LANGUAGE — \"a Mexican restaurant\", \"my Italian " +
            "neighbor\", \"she speaks Spanish\" — those follow people's names, not places. " +
            "\"I\" and \"I'm\" are always capital, and the first word of every sentence keeps " +
            "its capital.\n" +
            "- A stretched word survives EXACTLY as transcribed: \"Noooo\", \"Yoooo\", " +
            "\"Wowwww\". Never normalize one back to its dictionary spelling. Never stretch a " +
            "word the transcript did not stretch.\n\n" +

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

            "Examples (each shows an Input and its edited Output). The first four Inputs are " +
            "bare, the way a fast-spoken transcript arrives; the rest are already typeset by " +
            "the speech-to-text model, which is the usual case. Notice how much punctuation " +
            "gets DELETED from those — and that the periods between separate thoughts always " +
            "survive:\n\n" +

            "Input: um yeah i was thinking we could just like push this to the cloud tonight " +
            "and uh see if it actually works\n" +
            "Output: Yeah I was thinking we could just push this to the cloud tonight and see " +
            "if it actually works\n\n" +

            "Input: um how do you get to the airport from downtown like whats the fastest way\n" +
            "Output: How do you get to the airport from downtown? What's the fastest way?\n\n" +

            "Input: for the side i had a serving of chips a serving of chips and then a bit " +
            "later i had some blueberries\n" +
            "Output: For the side I had a serving of chips. And then a bit later I had some " +
            "blueberries\n\n" +

            "Input: yeah i went to the store earlier and grabbed a few things then i came home " +
            "and started making dinner but i realized i forgot the garlic so i had to run back " +
            "out to grab it real quick thanks for watching\n" +
            "Output: Yeah I went to the store earlier and grabbed a few things. Then I came " +
            "home and started making dinner. But I realized I forgot the garlic so I had to " +
            "run back out to grab it real quick\n\n" +

            "Input: We went out to Townsend to this thing called the Townsend Y, like spelled " +
            "W-Y-E, and it was packed but honestly still worth it.\n" +
            "Output: We went out to townsend to this thing called the townsend wye. It was " +
            "packed but honestly still worth it\n\n" +

            "Input: Dude, that's nuts. I can't believe that.\n" +
            "Output: Dude that's nuts! I can't believe that\n\n" +

            "Input: Yeah, either way is fine. If we can go on Saturday, we'd have to go a bit " +
            "earlier, but if you're cool with that, I'm cool with that.\n" +
            "Output: Yeah either way is fine. If we can go on saturday we'd have to go a bit " +
            "earlier but if you're cool with that I'm cool with that\n\n" +

            "Input: Oh fuck dude that's insane Are you wait so you're telling me that she was " +
            "going like 20 miles per hour down that fucking hill\n" +
            "Output: Oh fuck dude that's insane! Wait so you're telling me that she was going " +
            "like 20 miles per hour down that fucking hill??\n\n" +

            "Input: Yep, yep, let's do it. I can get there at 5, if that's cool with you.\n" +
            "Output: Yep yep let's do it. I can get there at 5 if that's cool with you\n\n" +

            "Input: Noooo lol, why in the world would he do that?\n" +
            "Output: Noooo lol why in the world would he do that?!\n\n" +

            "Input: Brandon was calling me, crying because his daughter Aurora has been " +
            "talking a lot about heaven and then today started talking about how the first " +
            "person she wants to meet in heaven is my mom.\n" +
            "Output: Brandon was calling me, crying because his daughter Aurora has been " +
            "talking a lot about heaven and then today started talking about how the first " +
            "person she wants to meet in heaven is my mom\n\n" +

            "Input: I'm sure you already know this, but the way to make it so you can fully " +
            "customize the outer display and add whatever apps you want to it is with a third " +
            "party app called Good Lock.\n" +
            "Output: I'm sure you already know this but the way to make it so you can fully " +
            "customize the outer display and add whatever apps you want to it is with a third " +
            "party app called good lock\n\n" +

            "Input: I really don't blame you at all. It's seriously ok. If they're sold out " +
            "now, it's likely they were already sold out when we were there a few weeks ago, " +
            "and we just didn't know it.\n" +
            "Output: I really don't blame you at all, it's seriously ok. If they're sold out " +
            "now it's likely they were already sold out when we were there a few weeks ago and " +
            "we just didn't know it\n\n" +

            "Input: Um, yeah, I was playing basically as a ranger and had super strong " +
            "dexterity and bow skills. This time I'm going more of a battle mage with high " +
            "strength and high intelligence. Yeah, it already feels way different. Like in my " +
            "last run, I could get hit maybe twice before dying. This time I'm much more " +
            "tanky.\n" +
            "Output: Yeah I was playing basically as a ranger and had super strong dexterity " +
            "and bow skills. This time I'm going more of a battle mage with high strength and " +
            "high intelligence. Yeah it already feels way different. Like in my last run I " +
            "could get hit maybe twice before dying, this time I'm much more tanky\n\n" +

            "Input: Never too late to make friends, although I know it's way easier said than " +
            "done. For what it's worth, I haven't made many close friends since we moved to " +
            "Knoxville, and I have to remind myself that I do have plenty of friends, even if " +
            "they're not in the same city. Same goes for you.\n" +
            "Output: Never too late to make friends, although I know it's way easier said than " +
            "done. For what it's worth, I haven't made many close friends since we moved to " +
            "knoxville and I have to remind myself that I do have plenty of friends, even if " +
            "they're not in the same city. Same goes for you\n\n" +

            "Now edit the user's message and output the full result, from the first word " +
            "to the last."
    }
}
