package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {

    @Test
    fun trimToLimit_keepsTextWhenNewlineIsOnlyAtEnd() {
        val singleHugeLine = "x".repeat(200) + "\n"
        val trimmed = CrashLog.trimToLimit(singleHugeLine, maxBytes = 100)
        assertEquals(100, trimmed.length)
        assertTrue(trimmed.endsWith("\n"))
    }

    @Test
    fun trimToLimit_stripsPartialLeadingLineWhenEarlierNewlineExists() {
        val text = "first-line-12345\nsecond-line\nthird-line\n"
        val trimmed = CrashLog.trimToLimit(text, maxBytes = 30)
        assertEquals("second-line\nthird-line\n", trimmed)
    }

    @Test
    fun trimToLimit_returnsUnchangedWhenWithinLimit() {
        val text = "short log\n"
        assertEquals(text, CrashLog.trimToLimit(text, maxBytes = 100))
    }
}
