package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLinkParserTest {

    @Test
    fun extractSharedUrl_parsesSharedBrowserLinksAndValidatesIntent() {
        val extracted = SharedLinkParser.extractSharedUrl(
            "android.intent.action.SEND",
            "text/plain",
            "Check out this daily puzzle: https://connections.swellgarfo.com/ play now!"
        )
        assertEquals("https://connections.swellgarfo.com/", extracted)

        val rawDomain = SharedLinkParser.extractSharedUrl("android.intent.action.SEND", "text/plain", "  worldle.teuteuf.fr  ")
        assertEquals("worldle.teuteuf.fr", rawDomain)

        assertNull(SharedLinkParser.extractSharedUrl("android.intent.action.MAIN", "text/plain", "https://example.com"))
        assertNull(SharedLinkParser.extractSharedUrl("android.intent.action.SEND", "image/png", "https://example.com"))
        assertNull(SharedLinkParser.extractSharedUrl("android.intent.action.SEND", "text/plain", "   "))
    }

    @Test
    fun sharedTextForLogging_keepsShortResultsAndDropsHugeText() {
        assertEquals("Framed #1234\nhttps://framed.wtf", SharedLinkParser.sharedTextForLogging("  Framed #1234\nhttps://framed.wtf \n"))
        assertEquals("", SharedLinkParser.sharedTextForLogging(null))
        assertEquals("", SharedLinkParser.sharedTextForLogging("x".repeat(9000)))
    }

    @Test
    fun extractSharedUrl_acceptsTextPlainWithMimeParameters() {
        assertEquals(
            "https://wordle.example.com/",
            SharedLinkParser.extractSharedUrl(
                "android.intent.action.SEND",
                "text/plain; charset=utf-8",
                "Play https://wordle.example.com/"
            )
        )
        assertEquals(
            "https://example.com/daily",
            SharedLinkParser.extractSharedUrl(
                "android.intent.action.SEND",
                "TEXT/PLAIN; charset=UTF-8",
                "https://example.com/daily"
            )
        )
    }

    @Test
    fun extractSharedUrl_stripsTrailingSentencePunctuationButKeepsBalancedParens() {
        assertEquals(
            "https://example.com/game",
            SharedLinkParser.extractSharedUrl(
                "android.intent.action.SEND",
                "text/plain",
                "Check (https://example.com/game)."
            )
        )
        assertEquals(
            "https://en.wikipedia.org/wiki/Word_(game)",
            SharedLinkParser.extractSharedUrl(
                "android.intent.action.SEND",
                "text/plain",
                "See https://en.wikipedia.org/wiki/Word_(game)!"
            )
        )
        assertEquals(
            "https://en.wikipedia.org/wiki/Word_(game)",
            SharedLinkParser.extractSharedUrl(
                "android.intent.action.SEND",
                "text/plain",
                "(https://en.wikipedia.org/wiki/Word_(game)),"
            )
        )
    }
}

