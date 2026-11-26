package com.dailygames.hub

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class WebsiteScraperRobustnessTest {
    init { SafeHttp.allowPrivateHostsForTests = true } // tests use a local server


    @Test
    fun metaContent_keepsApostrophesAndDoubleQuotes() {
        val html = """
            <html><head>
              <meta property="og:site_name" content="Today's Puzzle" />
              <meta name='description' content='Guess the "mystery" word in 6 tries'>
              <link rel="icon" href="/fav.png">
            </head></html>
        """.trimIndent()
        val meta = WebsiteScraper.parseHtmlMetadata(html, "https://example.com/")
        assertEquals("Today's Puzzle", meta.name)
        assertEquals("Guess the \"mystery\" word in 6 tries", meta.description)
        assertEquals("https://example.com/fav.png", meta.faviconUrl)
    }

    @Test
    fun decodeHtmlEntities_handlesNumericNamedAndDoesNotDoubleDecode() {
        assertEquals("Today’s game", WebsiteScraper.decodeHtmlEntities("Today&#8217;s game"))
        assertEquals("It's", WebsiteScraper.decodeHtmlEntities("It&#x27;s"))
        assertEquals("A — B", WebsiteScraper.decodeHtmlEntities("A &mdash; B"))
        assertEquals("&lt;b&gt;", WebsiteScraper.decodeHtmlEntities("&amp;lt;b&amp;gt;"))
        assertEquals("Tom & Jerry", WebsiteScraper.decodeHtmlEntities("Tom &amp; Jerry"))
    }

    @Test
    fun looksLikeBotChallenge_detectsInterstitials() {
        assertTrue(WebsiteScraper.looksLikeBotChallenge("<html><head><title>Just a moment...</title></head></html>"))
        assertTrue(WebsiteScraper.looksLikeBotChallenge("<script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate\"></script>"))
        assertFalse(WebsiteScraper.looksLikeBotChallenge("<html><head><title>Wordle</title></head></html>"))
    }

    @Test
    fun fetchAndScrape_reportsHttpErrorsAndChallengesInsteadOfParsingThem() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun respond(path: String, code: Int, body: String) {
            server.createContext(path) { ex ->
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        respond("/missing", 404, "<html><head><title>404 Not Found</title></head></html>")
        respond("/blocked", 503, "<html><head><title>Just a moment...</title></head></html>")
        respond("/cf", 200, "<html><head><title>Just a moment...</title></head></html>")
        respond("/ok", 200, "<html><head><meta property=\"og:site_name\" content=\"Café Daily\"></head></html>")
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"

            val missing = WebsiteScraper.fetchAndScrape("$base/missing")
            assertFalse(missing.getBoolean("ok"))
            assertEquals("http_404", missing.getString("error"))
            assertFalse(missing.getJSONObject("parsed").getString("name").contains("404"))

            assertEquals("http_503", WebsiteScraper.fetchAndScrape("$base/blocked").getString("error"))

            val cf = WebsiteScraper.fetchAndScrape("$base/cf")
            assertFalse(cf.getBoolean("ok"))
            assertEquals("bot_challenge", cf.getString("error"))

            val ok = WebsiteScraper.fetchAndScrape("$base/ok")
            assertTrue(ok.getBoolean("ok"))
            assertEquals("Café Daily", ok.getJSONObject("parsed").getString("name"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun detectResetSchedule_keepsThePublishedTimeZone() {
        assertEquals("09:00" to "America/New_York", WebsiteScraper.detectResetSchedule("A new puzzle is released daily at 9 AM ET."))
        assertEquals("22:00" to "America/New_York", WebsiteScraper.detectResetSchedule("New puzzle every night at 10:00 PM Eastern Time"))
        assertEquals("00:00" to "America/Los_Angeles", WebsiteScraper.detectResetSchedule("Resets at midnight PT every day"))
        assertEquals("00:00" to "Europe/Paris", WebsiteScraper.detectResetSchedule("The game resets at 00:00 CET"))
        assertEquals("00:00" to "utc", WebsiteScraper.detectResetSchedule("New puzzle refreshes at midnight UTC."))
        assertEquals("00:00" to "local", WebsiteScraper.detectResetSchedule("A new word every day at midnight your local time"))
        // Lower-case "et" / "pt" in ordinary prose is not a zone.
        assertEquals("00:00" to "local", WebsiteScraper.detectResetSchedule("Resets at midnight et cetera"))
        assertEquals("00:00" to "local", WebsiteScraper.detectResetSchedule("Play every afternoon with friends"))
    }

    @Test
    fun gameStoreResolvesNamedZonesAndFormatsInLocalTime() {
        val london = java.util.TimeZone.getTimeZone("Europe/London")
        assertEquals("America/New_York", GameStore.resolveZone("America/New_York", london).id)
        assertEquals("UTC", GameStore.resolveZone("utc", london).id)
        assertEquals("Europe/London", GameStore.resolveZone("Bogus/Zone", london).id)
        // 9 AM New York shown on a London phone in January = 2 PM.
        val jan15 = java.time.Instant.parse("2026-01-15T00:00:00Z").toEpochMilli()
        assertEquals("2:00 PM", GameStore.formatLocalResetTime("09:00", "America/New_York", london, jan15))
    }

    @Test
    fun ownScriptUrls_usesRegistrableDomainForMultiPartTlds() {
        val html = """
            <html><head>
              <script src="https://static.mygame.co.uk/app.js"></script>
              <script src="https://unrelated.co.uk/tracker.js"></script>
            </head></html>
        """.trimIndent()
        val scripts = WebsiteScraper.ownScriptUrls(html, "https://www.mygame.co.uk/play")
        assertEquals(listOf("https://static.mygame.co.uk/app.js"), scripts)
    }

    @Test
    fun detectResetScheduleDetail_requiresResetContextNearMidnightOrNoon() {
        val noContextMidnight = WebsiteScraper.detectResetScheduleDetail("Guess the Midnight Sun movie in 6 tries!")
        assertFalse(noContextMidnight.timeFound)
        val noContextNoon = WebsiteScraper.detectResetScheduleDetail("High Noon western trivia showdown")
        assertFalse(noContextNoon.timeFound)
        assertEquals("00:00", noContextNoon.time)

        val withContext = WebsiteScraper.detectResetScheduleDetail("New puzzle every day at midnight UTC.")
        assertTrue(withContext.timeFound)
        assertEquals("00:00", withContext.time)
        assertEquals("utc", withContext.zone)

        val withNoonContext = WebsiteScraper.detectResetScheduleDetail("Refreshes daily at noon ET")
        assertTrue(withNoonContext.timeFound)
        assertEquals("12:00", withNoonContext.time)
        assertEquals("America/New_York", withNoonContext.zone)
    }

    @Test
    fun countdownTimersAndStrayFooterUtcDoNotTriggerFalseSchedules() {
        val countdown = WebsiteScraper.detectResetScheduleDetail("New puzzle in 08:42:15. Terms of service (UTC).")
        assertFalse(countdown.timeFound)
        assertFalse(countdown.zoneFound)
        assertEquals("local", countdown.zone)

        val countdownShort = WebsiteScraper.detectResetScheduleDetail("Resets in 14:22!")
        assertFalse(countdownShort.timeFound)
    }

    @Test
    fun decodeHtmlEntities_handlesNumericAmpersandAndCaseSensitiveNamedEntities() {
        assertEquals("&lt;script&gt;", WebsiteScraper.decodeHtmlEntities("&#38;lt;script&#38;gt;"))
        assertEquals("Éclair & éclair", WebsiteScraper.decodeHtmlEntities("&Eacute;clair &amp; &eacute;clair"))
    }

    @Test
    fun stripsLargeScriptsBeforeTruncatingVisibleBodyText() {
        val bigScript = "<script>" + "var x = 'new puzzle at 10:00 AM';".repeat(1000) + "</script>"
        val html = "<html><head><title>Puzzle</title></head><body>$bigScript<p>Guess the word in 5 tries.</p></body></html>"
        val meta = WebsiteScraper.parseHtmlMetadata(html, "https://example.com/")
        assertFalse(meta.timeFound)
        assertEquals(5, meta.par)
    }

    @Test
    fun skipsMaskIconAndNonStringJsonLdNameAndSniffsMetaCharset() {
        val html = """
            <html><head>
              <meta charset="ISO-8859-1">
              <link rel="mask-icon" href="/safari-pinned-tab.svg" color="#000">
              <link rel="icon" href="/favicon.png">
              <script type="application/ld+json">{"@type":"Game","name":{"@value":"Wrapped"},"description":"Solve it."}</script>
              <title>Caf\u00e9</title>
            </head></html>
        """.trimIndent()
        val meta = WebsiteScraper.parseHtmlMetadata(html, "https://example.com/")
        assertEquals("https://example.com/favicon.png", meta.faviconUrl)
        assertEquals("ISO-8859-1".lowercase(), WebsiteScraper.charsetFromResponse(null, html.toByteArray(Charsets.ISO_8859_1)).name().lowercase())
    }

    @Test
    fun preservesHttp404EvenWhenRenderedAndRejectsBotChallengeRenderedOutput() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/missing") { ex ->
            val bytes = "<html><title>404 Not Found</title></html>".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html")
            ex.sendResponseHeaders(404, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/cf") { ex ->
            val bytes = "<html><title>Just a moment...</title></html>".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val res404 = WebsiteScraper.fetchAndScrape("$base/missing") {
                org.json.JSONObject().put("title", "404 Page Not Found").put("text", "Sorry, this page does not exist on this server.")
            }
            assertFalse(res404.getBoolean("ok"))
            assertEquals("http_404", res404.getString("error"))

            val resCfStillBlocked = WebsiteScraper.fetchAndScrape("$base/cf") {
                org.json.JSONObject().put("title", "Just a moment...").put("text", "Checking if the site connection is secure.")
            }
            assertFalse(resCfStillBlocked.getBoolean("ok"))
            assertFalse(resCfStillBlocked.getBoolean("rendered"))
            assertEquals("bot_challenge", resCfStillBlocked.getString("error"))

            val resCfBypassed = WebsiteScraper.fetchAndScrape("$base/cf") {
                org.json.JSONObject()
                    .put("title", "Real Game")
                    .put("text", "How to play\nGuess the hidden word in 6 tries.")
                    .put("url", "https://realgame.example/play")
            }
            assertTrue(resCfBypassed.getBoolean("ok"))
            assertTrue(resCfBypassed.getBoolean("rendered"))
            assertEquals("https://realgame.example/play", resCfBypassed.getString("finalUrl"))
        } finally {
            server.stop(0)
        }
    }
}

