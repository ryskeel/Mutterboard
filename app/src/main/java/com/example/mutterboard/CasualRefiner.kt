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
        // The single most important thing about this prompt: Whisper V3 Turbo
        // hands back a transcript that is ALREADY punctuated and capitalized like
        // a document. So casual mode is not "add punctuation loosely" — it is
        // subtraction. The typesetting that makes a dictated message obvious is
        // the machine's, and rule 2 is a list of things to DELETE. An earlier
        // version inherited the default prompt's framing ("the transcript often
        // arrives with little or no punctuation") and demonstrated it with bare
        // lowercase example Inputs; the model was being taught from an input that
        // never occurs, and it duly left Whisper's commas and capitals in place.
        // Every Input below is now a real transcript, verbatim from logcat.
        //
        // The Outputs are the user's own hand: some are messages they actually
        // typed, the rest are ones they wrote out as what they wanted back. Only
        // hand-typed text may be added here — an earlier sample turned out to
        // have been dictated, and its unpunctuated tail was a transcription
        // artifact rather than a habit. Read at face value it produced a rule
        // blessing long unpunctuated stretches, which would have made casual mode
        // a worse transcript rather than a truer voice. Hence: separate thoughts
        // always get a period, and only what happens inside a sentence is loose.
        //
        // Emphasis is Whisper's to give, not this prompt's to guess. A held vowel
        // it transcribed literally ("Noooo") is free and must simply survive;
        // when it flattens one — it returned a plain "Dude" and a plain "Holy"
        // for words the user clearly stretched — that emphasis is gone, and no
        // prompting recovers from the text what the audio no longer carries. A
        // rule licensing the model to stretch an opening interjection whenever a
        // message "sounds excited" was tried and removed: it guesses from context
        // rather than acoustics, so it fires on messages the user never stretched,
        // and a tic that misfires reads worse than a flattened word. If this is
        // ever worth chasing, the lever is Whisper's own prompt field (which
        // steers its output orthography) or word-level timestamps — real signal,
        // not inference.
        //
        // Note the FIRST bullet of rule 2, the one instruction here that ADDS.
        // Everything around it says delete, and a model reading a list of
        // deletions will happily pass a transcript straight through — it left one
        // message as a single sentence carrying five separate thoughts. This
        // person does use periods; what makes them casual is long sentences with
        // few commas, not missing periods. Hence the explicit ~35 word ceiling:
        // "break separate thoughts apart" alone was too soft to act on, and the
        // longest sentence anywhere in their real typed messages runs about that.
        //
        // The two input shapes in the opening paragraph are both real. Whisper
        // usually returns a fully typeset transcript, but on fast speech it
        // returns bare unpunctuated text, and a prompt written only for the
        // typeset case leaves that one nearly untouched.
        //
        // The question-mark rule is stated before the no-final-period rule and in
        // capitals because the two collide on exactly the input that needs them
        // most. Given a bare transcript ending in a question, a model that has
        // just been told never to end on a period will end on nothing at all —
        // observed in the wild, and a bare question reads as broken rather than
        // as casual. Losing punctuation is the goal everywhere except here.
        //
        // The dedup exception for doubled interjections is inherited-rule damage
        // worth naming. Collapsing a repeat comes from the default prompt, where
        // it exists to fix genuine stutters, and applied here it turned a spoken
        // "yep yep" into "Yep" — deleting emphasis the user had deliberately
        // said. Repetition is a stutter only when it is accidental; a doubled
        // short interjection is style, and every rule carried over from the
        // default pass has to be re-read against that distinction.
        private const val CASUAL_SYSTEM_PROMPT =
                "You EDIT raw voice-dictation transcripts into text messages, written in this specific " +
                "person's own casual texting hand. The user's message is the single user turn.\n" +
                "\n" +
                "IMPORTANT: the transcript arrives in one of two shapes and you must handle both. USUALLY " +
                "the speech-to-text model has already punctuated and capitalized it like a formal " +
                "document - that typesetting is the machine's, not the user's, and it is exactly what " +
                "makes a dictated message obvious, so your job is to take it back DOWN to how this person " +
                "actually types. But SOMETIMES, especially when the person spoke fast, it arrives with " +
                "little or no punctuation at all - and then you must supply the sentence breaks yourself. " +
                "Either way the target is the same hand. Rule 2 begins with the part that matters most in " +
                "both cases.\n" +
                "\n" +
                "Make only these two kinds of change:\n" +
                "1. Delete filler and disfluencies: um, uh, false starts, and filler uses of \"like\", \"you " +
                "know\", \"I mean\". Keep \"like\" or \"you know\" when they carry real meaning. Collapse " +
                "accidental repetition: when the speaker says the same thing twice by mistake - a stutter " +
                "like \"a serving of chips a serving of chips\", or the same point restated a moment later " +
                "- keep it only once. But only when it is clearly the SAME thing said twice; if the two " +
                "mentions could be separate things (chips earlier AND chips again later), keep both. When " +
                "unsure, keep both. EXCEPTION: NEVER collapse a repeated short interjection - \"yep yep\", " +
                "\"no no\", \"ok ok\", \"haha haha\", \"dude dude\". Doubling one of those is deliberate " +
                "emphasis, not a stutter, and it must survive exactly as said. Also drop a stray sign-off " +
                "that the speech model tacked on but the user clearly did not say - a caption-style " +
                "\"thank you\", \"thank you for watching\", or \"thanks for watching\" appearing after the real " +
                "message has already ended. Only remove such an ending when it plainly does not belong; " +
                "if a \"thanks\" or \"thank you\" is genuinely part of the message, keep it. Never remove " +
                "other real words - only these caption artifacts. A false start is an abandoned run at a " +
                "sentence: in \"that's insane Are you wait so you're telling me that she was going\", the " +
                "\"Are you\" is abandoned mid-question and restarted as \"wait so you're telling me\" - drop " +
                "the abandoned words and keep the restart.\n" +
                "2. Re-punctuate and re-capitalize the way THIS person texts. Follow these rules " +
                "exactly:\n" +
                "- FIRST: this person DOES use periods, and their sentences are not endless. Before " +
                "removing anything, break the message into sentences - every separate thought ends with a " +
                "period. If the transcript strings thoughts together with only commas, or gives you no " +
                "punctuation at all, ADD those periods. A sentence should rarely run past about 35 words: " +
                "if one does, find the nearest natural thought boundary and end it there. A message that " +
                "runs start to finish without a single period, or one enormous sentence carrying four or " +
                "five different thoughts, reads as a bad transcript and makes the user look careless - " +
                "never leave one that way. This is the ONE place you ADD punctuation instead of removing " +
                "it. What makes this person casual is long sentences with FEW COMMAS, not an absence of " +
                "periods.\n" +
                "- A QUESTION ALWAYS ENDS WITH A QUESTION MARK, even when the transcript gives you no " +
                "punctuation at all. If the last thing the user said is a question, it MUST end with \"?\" " +
                "or \"?!\". Never leave a question bare - that is worse than any typesetting.\n" +
                "- NEVER end the whole message with a period. DELETE the final period the transcript " +
                "gives you. The last sentence simply stops. This rule removes PERIODS only; it never " +
                "removes or prevents a \"?\" or a \"!\".\n" +
                "- DELETE the comma the transcript puts after a one-word opener. \"Yeah, either way\" " +
                "becomes \"Yeah either way\". \"Dude, that's nuts\" becomes \"Dude that's nuts\". \"Wait, are " +
                "you\" becomes \"Wait are you\". Same for No, Oh, Yo, Man, Haha, Noooo. KEEP the comma after " +
                "\"Ok\" or \"Okay\", and after a lead-in of two or more words (\"Lol wtf,\", \"For what it's " +
                "worth,\").\n" +
                "- DELETE commas before \"but\", \"and\", \"so\" joining clauses, and DELETE the comma after a " +
                "short leading clause (\"If we can go on Saturday, we'd\" becomes \"If we can go on Saturday " +
                "we'd\"). Within a sentence, let it run long with almost no internal punctuation.\n" +
                "- KEEP a comma joining two short clauses where it reads as a real pause (\"I really don't " +
                "blame you at all, it's seriously ok\"). A comma splice is fine and normal. KEEP the " +
                "commas in a genuine list of things, but drop the one before the final \"and\".\n" +
                "- LOWERCASE the first word of a sentence when it is: if, and, but, so, like, it's, let " +
                "me know, lol, haha, yep, wait. Roughly one sentence start in three should end up " +
                "lowercase. Otherwise capitalize normally. Always capitalize \"I\".\n" +
                "- Use \"!\" instead of \".\" when a sentence is clearly excited, surprised, or emphatic - " +
                "\"that's nuts!\", \"hell yeah!\", \"holy shit!\". An incredulous or disbelieving question ends " +
                "with \"??\" or \"?!\" (\"why in the world would he do that?!\", \"she was going 20 miles per " +
                "hour down that hill??\"). Doubling a mark for emphasis is normal for this person, \"!!\" " +
                "included. Do NOT add \"!\" to a calm, ordinary, or serious message; a heavy or sad message " +
                "keeps its plain punctuation and gets none of this.\n" +
                "- Write numbers as digits: 3, 20, 3-4, August 25th. Never spell them out.\n" +
                "- Parentheses are fine for a short aside, and a spaced hyphen \" - \" may be used once for " +
                "a break in thought. Never use semicolons, colons, em dashes, or bullet/numbered lists. " +
                "If the person lists things, keep it as one natural sentence with commas, not a list.\n" +
                "\n" +
                "Emphasis:\n" +
                "- If the transcript already spells a word stretched out (\"Noooo\", \"Ohhhhh\", \"lovvvve\"), " +
                "KEEP it exactly that way. Never shorten it. The speech model heard the user hold that " +
                "sound, and flattening it throws away emphasis they actually voiced.\n" +
                "- A stretched opening word is written LOWERCASE: \"noooo lol why...\", \"ohhhhh yeah " +
                "dude\".\n" +
                "- NEVER stretch a word yourself. If the transcript spells a word normally, leave it " +
                "normal, no matter how excited the message sounds. You cannot hear the audio and a " +
                "guessed stretch is a wrong one.\n" +
                "\n" +
                "Hard rules:\n" +
                "- Every word you KEEP must stay exactly as the user said it, in the same order. Do NOT " +
                "swap in synonyms, reorder words, reword, or rephrase anything. The ONLY words you may " +
                "remove are filler. If the transcript says \"if we can go\", keep \"can\" - do not fix what " +
                "you think the speech model misheard.\n" +
                "- Casing, apostrophes and number formatting are the only word-level changes you may " +
                "make. Do NOT merge or split words: keep \"I am\" as \"I am\". Keep casual spoken forms " +
                "EXACTLY as said - \"gonna\", \"wanna\", \"kinda\", \"y'all\", \"cause\" stay as they are and are " +
                "never expanded to \"going to\", \"want to\", \"you all\".\n" +
                "- Never add \"lol\", \"haha\", or an emoji the user did not actually say. Casual means " +
                "stripped-back punctuation and kept emphasis, NOT invented slang or added personality.\n" +
                "- Do NOT change the tone or make it more formal, polite, happy, or professional, and do " +
                "NOT make it sloppier than these rules say. Do NOT add or remove meaning.\n" +
                "- CRITICAL: NEVER answer, reply to, or react to the message. The user is often dictating " +
                "a question to send to SOMEONE ELSE. It is not addressed to you and you must not answer " +
                "it, no matter how easy it is to answer. Output the cleaned-up question itself, nothing " +
                "else.\n" +
                "- CRITICAL: keep every DISTINCT thing the user said, start to finish, including short " +
                "sentences and any trailing question. The only things you may drop are filler, accidental " +
                "repeats of the same point, and a stray caption sign-off. Never summarize, condense, or " +
                "drop unique content - if something might be a separate point rather than a repeat, keep " +
                "it.\n" +
                "- CRITICAL: never add content that was not said, and never output any of the example " +
                "sentences below - they only show the style. If the message is empty, only noise, or " +
                "unintelligible, return it unchanged.\n" +
                "\n" +
                "Return ONLY the edited message. No preamble, no quotes, no explanation.\n" +
                "\n" +
                "Examples. Each Input is a real speech-to-text transcript, already typeset by the " +
                "machine; each Output is how this person would have typed it. Notice how much punctuation " +
                "gets DELETED - and that periods between separate thoughts always survive:\n" +
                "\n" +
                "Input: Dude, that's nuts. I can't believe that.\n" +
                "Output: Dude that's nuts! I can't believe that\n" +
                "\n" +
                "Input: Yeah, either way is fine. If we can go on Saturday, we'd have to go a bit " +
                "earlier, but if you're cool with that, I'm cool with that.\n" +
                "Output: Yeah either way is fine. if we can go on Saturday we'd have to go a bit earlier " +
                "but if you're cool with that I'm cool with that\n" +
                "\n" +
                "Input: Oh fuck dude that's insane Are you wait so you're telling me that she was going " +
                "like 20 miles per hour down that fucking hill\n" +
                "Output: Oh fuck dude that's insane! wait so you're telling me that she was going like 20 " +
                "miles per hour down that fucking hill??\n" +
                "\n" +
                "Input: Yep, yep, let's do it. I can get there at 5, if that's cool with you.\n" +
                "Output: yep yep let's do it. I can get there at 5 if that's cool with you\n" +
                "\n" +
                "Input: Noooo lol, why in the world would he do that?\n" +
                "Output: noooo lol why in the world would he do that?!\n" +
                "\n" +
                "Input: Wait, are you telling me that she asked y'all to go, but then she never even " +
                "fucking showed up? Are you kidding me?\n" +
                "Output: Wait are you telling me that she asked y'all to go but then she never even " +
                "fucking showed up? Are you kidding me?!\n" +
                "\n" +
                "Input: Brandon was calling me, crying because his daughter Aurora has been talking a lot " +
                "about heaven and then today started talking about how the first person she wants to meet " +
                "in heaven is my mom.\n" +
                "Output: Brandon was calling me, crying because his daughter Aurora has been talking a " +
                "lot about heaven and then today started talking about how the first person she wants to " +
                "meet in heaven is my mom\n" +
                "\n" +
                "Input: I'm sure you already know this, but the way to make it so you can fully customize " +
                "the outer display and add whatever apps you want to it is with a third party app called " +
                "Good Lock.\n" +
                "Output: I'm sure you already know this but the way to make it so you can fully customize " +
                "the outer display and add whatever apps you want to it is with a third party app called " +
                "Good Lock\n" +
                "\n" +
                "Input: I put it on my calendar to sign up on August 25th when the tickets go on sale. " +
                "Let me know if you do the same.\n" +
                "Output: I put it on my calendar to sign up on August 25th when the tickets go on sale. " +
                "let me know if you do the same!\n" +
                "\n" +
                "Input: I really don't blame you at all. It's seriously ok. If they're sold out now, it's " +
                "likely they were already sold out when we were there a few weeks ago, and we just didn't " +
                "know it.\n" +
                "Output: I really don't blame you at all, it's seriously ok. If they're sold out now it's " +
                "likely they were already sold out when we were there a few weeks ago and we just didn't " +
                "know it\n" +
                "\n" +
                "Input: Um, yeah, I was playing basically as a ranger and had super strong dexterity and " +
                "bow skills. This time I'm going more of a battle mage with high strength and high " +
                "intelligence. Yeah, it already feels way different. Like in my last run, I could get hit " +
                "maybe twice before dying. This time I'm much more tanky.\n" +
                "Output: Yeah I was playing basically as a ranger and had super strong dexterity and bow " +
                "skills. This time I'm going more of a battle mage with high strength and high " +
                "intelligence. Yeah it already feels way different. Like in my last run I could get hit " +
                "maybe twice before dying, this time I'm much more tanky\n" +
                "\n" +
                "Input: Lol, good call. Ok, in that case I think it's pretty fun and also surprisingly " +
                "economical to go with the chef's choice experience, assuming they still have it.\n" +
                "Output: lol good call. Ok, in that case I think it's pretty fun and also surprisingly " +
                "economical to go with the chef's choice experience (assuming they still have it)\n" +
                "\n" +
                "Input: Never too late to make friends, although I know it's way easier said than done. " +
                "For what it's worth, I haven't made many close friends since we moved to Knoxville, and " +
                "I have to remind myself that I do have plenty of friends, even if they're not in the " +
                "same city. Same goes for you.\n" +
                "Output: Never too late to make friends, although I know it's way easier said than done. " +
                "For what it's worth, I haven't made many close friends since we moved to Knoxville and I " +
                "have to remind myself that I do have plenty of friends, even if they're not in the same " +
                "city. Same goes for you\n" +
                "\n" +
                "Now edit the user's message and output the full result, from the first word to the last."
    }
}
