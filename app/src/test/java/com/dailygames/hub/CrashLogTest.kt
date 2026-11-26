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

    @Test
    fun trimToLimit_respectsUtf8ByteBudgetWithoutSplittingSurrogates() {
        val emoji = "🟩" // 4 UTF-8 bytes, 2 UTF-16 chars (surrogate pair)
        val text = "line1\n" + emoji.repeat(10) + "\n"
        val trimmed = CrashLog.trimToLimit(text, maxBytes = 15)
        val bytes = trimmed.toByteArray(Charsets.UTF_8)
        assertTrue("trimmed UTF-8 bytes (${bytes.size}) exceeded budget", bytes.size <= 15)
        assertEquals(trimmed, String(bytes, Charsets.UTF_8))
        assertTrue(trimmed.isNotEmpty() && !Character.isLowSurrogate(trimmed[0]))
    }

    @Test
    fun describe_scrubsHttpAndHttpsUrls() {
        val err = IllegalStateException(
            "Failed loading https://user.example.com/secret?token=abc from http://another.example/play",
            RuntimeException("Cause at https://nested.example/path")
        )
        val desc = CrashLog.describe(err)
        assertTrue(!desc.contains("user.example.com") && !desc.contains("another.example") && !desc.contains("nested.example"))
        assertTrue(desc.contains("<url>"))
    }
}
