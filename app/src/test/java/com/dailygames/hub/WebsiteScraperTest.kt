package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class WebsiteScraperTest {
    init { SafeHttp.allowPrivateHostsForTests = true } // tests use a local server


    @Test
    fun parseHtmlMetadata_extractsTitleFaviconResetParAndCategory() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <title>Worldle - Guess the Country in 6 tries | Daily Geography Game</title>
              <meta property="og:site_name" content="Worldle &amp; Map" />
              <meta name="description" content="Guess the country in 6 guesses! New puzzle refreshes at midnight UTC." />
              <link rel="apple-touch-icon" href="/icons/apple-touch-icon.png" />
              <script>const seconds = 123; const afternoon = "afternoon";</script>
            </head>
            <body>Daily geography puzzle</body>
            </html>
        """.trimIndent()

        val meta = WebsiteScraper.parseHtmlMetadata(html, "https://worldle.teuteuf.fr/play")
        assertEquals("Worldle & Map", meta.name)
        assertEquals("Guess the country in 6 guesses! New puzzle refreshes at midnight UTC.", meta.description)
        assertEquals("https://worldle.teuteuf.fr/icons/apple-touch-icon.png", meta.faviconUrl)
        assertEquals("00:00", meta.resetTime)
        assertEquals("utc", meta.resetZone)
        assertEquals("tries", meta.metricType)
        assertEquals(6, meta.par)
        assertEquals("Geography", meta.category)

        val json = meta.toJson()
        assertEquals("Worldle & Map", json.getString("name"))
        assertEquals("Geography", json.getString("category"))
    }

    @Test
    fun parseHtmlMetadata_handlesCustomResetTimeTimerPointsAndFallbackFavicon() {
        val html = """
            <html>
            <head>
              <title>SpeedSudoku: Daily Number Logic</title>
              <meta content="Beat the fastest time on the stopwatch! Daily reset at 07:30 pm." name="description" />
            </head>
            </html>
        """.trimIndent()

        val meta = WebsiteScraper.parseHtmlMetadata(html, "speed-sudoku.io")
        assertEquals("SpeedSudoku", meta.name)
        assertEquals("https://www.google.com/s2/favicons?domain=speed-sudoku.io&sz=128", meta.faviconUrl)
        assertEquals("19:30", meta.resetTime)
        assertEquals("local", meta.resetZone)
        assertEquals("time", meta.metricType)
        assertEquals(60, meta.par)
        assertEquals("Math & Logic", meta.category)
    }

    @Test
    fun detectCategoriesAndSchedules_coversMusicTriviaNoonWordBoundaryAndAfternoon() {
        assertEquals("12:00" to "utc", WebsiteScraper.detectResetSchedule("Refreshes at noon GMT every day"))
        // "afternoon" must not falsely match "noon" (K11)
        assertEquals("00:00" to "local", WebsiteScraper.detectResetSchedule("Play this puzzle in the afternoon"))
        assertEquals("00:30" to "local", WebsiteScraper.detectResetSchedule("new puzzle at 12:30 am"))
        assertEquals("points" to 100, WebsiteScraper.detectMetricAndPar("Earn the max score and points"))
        assertEquals("Music & Media", WebsiteScraper.detectCategory("Guess the song and album", "bandle.app"))
        assertEquals("Trivia", WebsiteScraper.detectCategory("Daily history quiz", "quiz.org"))
        assertEquals("Word", WebsiteScraper.detectCategory("Spell letters", "wordgame.com"))
    }

    @Test
    fun fetchAndScrape_fetchesFromLocalHttpServerOmitsRawHtmlAndHandlesErrors() {
        ServerSocket(0).use { server ->
            val port = server.localPort
            val serverThread = Thread {
                try {
                    server.accept().use { client ->
                        val body = """
                            <html>
                            <head>
                              <title>Connections | Daily Word Puzzle</title>
                              <link href="/favicon.png" rel="icon" />
                            </head>
                            <body>Group words in 4 tries! Refreshes at midnight.</body>
                            </html>
                        """.trimIndent()
                        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                        client.getOutputStream().write(response.toByteArray())
                        client.getOutputStream().flush()
                    }
                } catch (_: Exception) {
                }
            }
            serverThread.start()

            val okRes = WebsiteScraper.fetchAndScrape("http://127.0.0.1:$port/game")
            assertTrue(okRes.getBoolean("ok"))
            // P9: raw HTML must not be included in the bridge payload
            assertFalse(okRes.has("html"))
            val parsed = okRes.getJSONObject("parsed")
            assertEquals("Connections", parsed.getString("name"))
            assertEquals("tries", parsed.getString("metricType"))
            assertEquals(4, parsed.getInt("par"))
            serverThread.join(2000)
        }

        val errRes = WebsiteScraper.fetchAndScrape("127.0.0.1:1/unreachable-game")
        assertFalse(errRes.getBoolean("ok"))
        assertEquals("127", errRes.getJSONObject("parsed").getString("name"))
    }
}
