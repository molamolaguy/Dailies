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
}
