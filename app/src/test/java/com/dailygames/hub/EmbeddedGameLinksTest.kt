package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.8.13: frames on a page that could be a game shown inside it (the hub asks before adding a game twice). */
class EmbeddedGameLinksTest {
    @Test
    fun keepsGameFramesAndResolvesRelativeLinks() {
        val html = """
            <html><body>
            <iframe src="https://www.nytimes.com/games/wordle/index.html" width="600" height="800"></iframe>
            <iframe src='/embed/mini?day=12'></iframe>
            <iframe src="https://www.youtube.com/embed/abc"></iframe>
            <iframe src="https://googleads.g.doubleclick.net/pagead/ads?x=1"></iframe>
            <iframe src="javascript:void(0)"></iframe>
            </body></html>"""
        val out = WebsiteScraper.embeddedGameLinks(html, "https://games.example/daily/", listOf("https://krillion.io/play", "https://www.nytimes.com/games/wordle/index.html"))
        assertEquals(
            listOf("https://www.nytimes.com/games/wordle/index.html", "https://games.example/embed/mini?day=12", "https://krillion.io/play"),
            out
        )
    }

    @Test
    fun aPageWithoutFramesHasNone() {
        assertEquals(emptyList<String>(), WebsiteScraper.embeddedGameLinks("<p>No frames</p>", "https://a.example/"))
    }
}
