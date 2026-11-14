package com.example.mutterboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DropFinalPeriodTest {

    private fun drop(s: String) = DictationSession.dropFinalPeriod(s)

    @Test
    fun dropsOnlyALoneFinalPeriod() {
        assertEquals("See you there", drop("See you there."))
        assertEquals("I'm late. See you there", drop("I'm late. See you there."))
        assertEquals("Are you coming?", drop("Are you coming?"))
        assertEquals("No way!", drop("No way!"))
        assertEquals("So anyway...", drop("So anyway..."))
        assertEquals("See you there", drop("See you there"))
    }
}
