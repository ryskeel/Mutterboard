package com.example.mutterboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqRefinerTest {

    private val refiner = GroqRefiner("test-key")

    @Test
    fun flagsAnswerToDictatedQuestionAsInvented() {
        // The real-world regression this guard exists for: the user dictated a
        // question and the refiner model answered it instead of editing it.
        val raw = "how do you quickly change to another keyboard on a samsung phone"
        val answer = "To quickly switch to another keyboard on a Samsung phone, " +
            "you can swipe down on the keyboard with one finger, or tap the " +
            "keyboard icon, usually found on the bottom left corner of the " +
            "keyboard, and select the desired keyboard from the list."
        assertTrue(refiner.isInvented(raw, answer))
    }

    @Test
    fun acceptsFaithfulEdit() {
        val raw = "um yeah i was thinking we could just like push this to the " +
            "cloud tonight and uh see if it actually works"
        val edited = "Yeah I was thinking we could just push this to the cloud " +
            "tonight and see if it actually works."
        assertFalse(refiner.isInvented(raw, edited))
    }

    @Test
    fun acceptsEditedQuestionOfItself() {
        val raw = "how do you um how do you quickly change to another keyboard " +
            "on a samsung phone"
        val edited = "How do you quickly change to another keyboard on a Samsung phone?"
        assertFalse(refiner.isInvented(raw, edited))
    }

    @Test
    fun toleratesAFewNovelWordsOnShortMessages() {
        // A benign reword (the kind the user accepts) introduces a word or two;
        // that must not trip the hard gate and cost them the polished output.
        val raw = "im gonna head out in like five minutes"
        val edited = "I'm going to head out in about five minutes."
        assertFalse(refiner.isInvented(raw, edited))
    }

    @Test
    fun toleratesApostropheAndCasingFixes() {
        val raw = "im not sure whats going on with dons phone"
        val edited = "I'm not sure what's going on with Don's phone."
        assertFalse(refiner.isInvented(raw, edited))
    }

    @Test
    fun flagsFabricatedContentOnLongerMessages() {
        val raw = "remind me to send the report tomorrow morning"
        val fabricated = "Sure, I will remind you to send the report tomorrow " +
            "morning. Is there anything else you would like me to help with today?"
        assertTrue(refiner.isInvented(raw, fabricated))
    }
}
