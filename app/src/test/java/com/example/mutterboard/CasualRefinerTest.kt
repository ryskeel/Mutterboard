package com.example.mutterboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors [GroqRefinerTest] against the casual pass. The point of the overlap is
 * that casual mode must inherit the SAME anti-answer protection, not a weaker
 * one — plus the casual-specific cases proving the guard doesn't misread this
 * mode's own output (a dropped terminal period, a lowercase sentence start,
 * digits) as fabrication.
 */
class CasualRefinerTest {

    private val refiner = CasualRefiner("test-key")

    @Test
    fun flagsAnswerToDictatedQuestionAsInvented() {
        // The regression the hard gate exists for, re-pinned here because casual
        // mode reaches the same model with a different prompt and must not be a
        // way around the guard.
        val raw = "how do you quickly change to another keyboard on a samsung phone"
        val answer = "To quickly switch to another keyboard on a Samsung phone, " +
            "you can swipe down on the keyboard with one finger, or tap the " +
            "keyboard icon, usually found on the bottom left corner of the " +
            "keyboard, and select the desired keyboard from the list."
        assertTrue(refiner.isInvented(raw, answer))
    }

    @Test
    fun flagsFabricatedContentOnLongerMessages() {
        val raw = "remind me to send the report tomorrow morning"
        val fabricated = "Sure, I will remind you to send the report tomorrow " +
            "morning. Is there anything else you would like me to help with today?"
        assertTrue(refiner.isInvented(raw, fabricated))
    }

    @Test
    fun acceptsCasualEditWithNoTerminalPeriod() {
        val raw = "um im sure you already know this but the way to make it so you " +
            "can like fully customize the outer display is with a third party app " +
            "called good lock"
        val edited = "I'm sure you already know this but the way to make it so you " +
            "can fully customize the outer display is with a third party app " +
            "called Good Lock"
        assertFalse(refiner.isInvented(raw, edited))
    }

    @Test
    fun acceptsLowercaseSentenceStartAfterAPeriod() {
        // Casual mode's signature move: a period mid-message, then a lowercase
        // next word. The tokenizer lowercases anyway, so this must be invisible
        // to the guard.
        val raw = "i put it on my calendar to sign up when the tickets go on sale " +
            "let me know if you do the same"
        val edited = "I put it on my calendar to sign up when the tickets go on " +
            "sale. let me know if you do the same!"
        assertFalse(refiner.isInvented(raw, edited))
    }

    @Test
    fun acceptsLongSentenceLeftUnbroken() {
        // Casual mode leaves a single long sentence strung together with
        // "and"/"but" alone rather than chopping it into correct short ones. (It
        // does still close off separate thoughts with a period — the loose
        // punctuation is within a sentence, not between them.)
        val raw = "um im sure you already know this but the way to make it so you " +
            "can fully customize the outer display and add whatever apps you want " +
            "to it is with a third party app called good lock"
        val edited = "I'm sure you already know this but the way to make it so you " +
            "can fully customize the outer display and add whatever apps you want " +
            "to it is with a third party app called Good Lock"
        assertFalse(refiner.isInvented(raw, edited))
    }

    @Test
    fun acceptsCommaSpliceAndDroppedFiller() {
        val raw = "i really dont blame you at all its seriously ok um if theyre " +
            "sold out now we just didnt know it"
        val edited = "I really don't blame you at all, it's seriously ok. If " +
            "they're sold out now we just didn't know it"
        assertFalse(refiner.isInvented(raw, edited))
    }

    @Test
    fun acceptsSpelledNumberRenderedAsDigits() {
        // Digits are one of the three word-level changes casual mode is allowed
        // (alongside casing and apostrophes), so "three" to "3" must survive the
        // gate even on a short message where the novel-word budget is tightest.
        val raw = "theyre known for waiting like three months to push major updates"
        val edited = "they're known for waiting like 3 months to push major updates"
        assertFalse(refiner.isInvented(raw, edited))
    }

    @Test
    fun acceptsStretchedWordCarriedThrough() {
        // Whisper transcribes a held vowel literally, and casual mode keeps it.
        // Carrying it through is what keeps the pair token-identical here —
        // normalizing "ohhhhh" to "oh" would spend novel-word budget for nothing.
        val raw = "Ohhhhh yeah dude, I am really down to do that."
        val edited = "Ohhhhh yeah dude I am really down to do that"
        assertFalse(refiner.isInvented(raw, edited))
    }

    @Test
    fun acceptsParentheticalAside() {
        val raw = "i think its pretty fun and also surprisingly economical to go " +
            "with the chefs choice experience assuming they still have it"
        val edited = "I think it's pretty fun and also surprisingly economical to " +
            "go with the chef's choice experience (assuming they still have it)"
        assertFalse(refiner.isInvented(raw, edited))
    }
}
