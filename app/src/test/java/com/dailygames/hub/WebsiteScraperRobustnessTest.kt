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
    fun looksLikeBotChallenge_detectsInterstitialsWithoutFlaggingCloudflarePassiveJsd() {
        assertTrue(WebsiteScraper.looksLikeBotChallenge("<html><head><title>Just a moment...</title></head></html>"))
        assertTrue(WebsiteScraper.looksLikeBotChallenge("<script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate\"></script>"))
        assertFalse(WebsiteScraper.looksLikeBotChallenge("<html><head><title>Wordle</title></head></html>"))
        // Cloudflare's passive JSD telemetry script (used on fe-guess.com and many normal 200 OK sites) is not a block wall.
        val feGuessHtml = """
            <!doctype html><html><head>
            <title>FE Guess — Daily Fire Emblem Character Guessing Game</title>
            <meta name="description" content="Guess the Fire Emblem character of the day!" />
            </head><body><div id="root"></div>
            <script>(function(){var u='/cdn-cgi/challenge-platform/scripts/jsd/main.js?ray=abc';})();</script>
            </body></html>
        """.trimIndent()
        assertFalse(WebsiteScraper.looksLikeBotChallenge(feGuessHtml))
        assertEquals("ok", GameHealth.classify("https://fe-guess.com/", 200, "https://fe-guess.com/", WebsiteScraper.looksLikeBotChallenge(feGuessHtml)).first)
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

    @Test
    fun expandedResetSchedulesAndZones_coverRealWorldPhrasingsAndUtcOffsets() {
        // Nintendoku: "06:00 German time"
        val de = WebsiteScraper.detectResetScheduleDetail("A new puzzle appears every day at 06:00 German time, identical for everyone worldwide.")
        assertTrue(de.timeFound)
        assertTrue(de.zoneFound)
        assertEquals("06:00", de.time)
        assertEquals("Europe/Berlin", de.zone)

        // Farflung: "opens at midnight in your local time"
        val ff = WebsiteScraper.detectResetScheduleDetail("A new expedition opens at midnight in your local time.")
        assertTrue(ff.timeFound)
        assertTrue(ff.zoneFound)
        assertEquals("00:00", ff.time)
        assertEquals("local", ff.zone)

        // putt.day: "new one at midnight Pacific"
        val putt = WebsiteScraper.detectResetScheduleDetail("Same hole for everyone, new one at midnight Pacific.")
        assertTrue(putt.timeFound)
        assertTrue(putt.zoneFound)
        assertEquals("00:00", putt.time)
        assertEquals("America/Los_Angeles", putt.zone)

        // Inverted word order & 12:00 midnight / 12 noon
        assertEquals("00:00" to "utc", WebsiteScraper.detectResetSchedule("At midnight UTC, a new puzzle unlocks."))
        assertEquals("00:00" to "America/New_York", WebsiteScraper.detectResetSchedule("Daily puzzle resets at 12:00 midnight ET."))
        assertEquals("12:00" to "Europe/London", WebsiteScraper.detectResetSchedule("New round unlocks at 12 noon UK time."))

        // Explicit numeric UTC/GMT offsets normalize cleanly to UTC HH:MM
        val utcPlus8 = WebsiteScraper.detectResetScheduleDetail("Daily reset at 08:00 UTC+8")
        assertEquals("00:00", utcPlus8.time)
        assertEquals("utc", utcPlus8.zone)
        assertTrue(utcPlus8.zoneFound)

        val gmtMinus5 = WebsiteScraper.detectResetScheduleDetail("New puzzle available at 19:00 GMT-5")
        assertEquals("00:00", gmtMinus5.time)
        assertEquals("utc", gmtMinus5.zone)

        // Non-clock ratios in prose must not be mistaken for 24h reset times
        val ratio = WebsiteScraper.detectResetScheduleDetail("Daily map game drawn at scale 1:50.")
        // wait: "at scale 1:50" has "scale " before 1:50, not "at " right before 1:50!
        assertFalse(ratio.timeFound)
    }

    @Test
    fun resolveUrlAndOwnScripts_handleBareOriginRelativePathsAndVendorBundles() {
        val html = """
            <html><head>
              <script src="../js/jquery-3.6.0.min.js"></script>
              <script src="/_next/static/chunks/polyfills-abc.js"></script>
              <script src="/_next/static/chunks/9812-hash.js"></script>
              <script type="module" crossorigin src="./assets/index-Cex6E_IQ.js"></script>
            </head></html>
        """.trimIndent()
        val scripts = WebsiteScraper.ownScriptUrls(html, "https://farflung.game", max = 2)
        assertEquals(
            listOf("https://farflung.game/assets/index-Cex6E_IQ.js", "https://farflung.game/_next/static/chunks/9812-hash.js"),
            scripts
        )
        assertEquals("https://www.gamedle.wtf/js/platform0.3.js", WebsiteScraper.resolveUrl("https://www.gamedle.wtf/", "../js/platform0.3.js"))
        assertEquals("America/Toronto", WebsiteScraper.scriptZoneHint("""dayjs.tz(now, "America/Toronto")"""))
        assertEquals("Europe/Berlin", WebsiteScraper.scriptZoneHint("""DateTime.now().setZone("Europe/Berlin")"""))
        assertEquals("utc", WebsiteScraper.scriptZoneHint("""const day=Math.floor(Date.UTC(d.getUTCFullYear(),d.getUTCMonth(),d.getUTCDate())/864e5)"""))
    }

    @Test
    fun targetFormatDescriptionsAndCategories_handleRealGameArchetypes() {
        // Farflung
        val ffHtml = """
            <html><head>
              <title>Farflung Geography Game - Play the Daily Map Quiz</title>
              <meta property="og:site_name" content="Farflung" />
              <meta name="description" content="Play Farflung, the official daily geography game. Pin a country for each of five prompts in this free online map quiz." />
            </head><body>A new expedition opens at midnight in your local time.</body></html>
        """.trimIndent()
        val ff = WebsiteScraper.parseHtmlMetadata(ffHtml, "https://farflung.game")
        assertEquals("Farflung", ff.name)
        assertEquals("Geography", ff.category)
        assertEquals("5 prompts", ff.targetFormat)
        assertEquals("Pin a country for each of five prompts in this map quiz.", ff.description)
        assertEquals("00:00", ff.resetTime)
        assertEquals("local", ff.resetZone)
        assertTrue(ff.timeFound && ff.zoneFound)

        // Nintendoku (Gaming & RPG, German time, 3x3 grid / 12 guesses)
        val ninHtml = """
            <html><head>
              <title>NintenDoku: The Daily Nintendo Grid Puzzle</title>
              <meta property="og:description" content="A new Nintendo grid puzzle every day. Fill the 3x3 grid with matching games. 7,700+ titles from NES to Switch 2." />
            </head><body><div>A new puzzle appears every day at 06:00 German time.</div><div>You have 12 guesses.</div></body></html>
        """.trimIndent()
        val nin = WebsiteScraper.parseHtmlMetadata(ninHtml, "https://www.n-puzzle.com/en/nintendoku")
        assertEquals("NintenDoku", nin.name)
        assertEquals("Gaming & RPG", nin.category)
        assertEquals("06:00", nin.resetTime)
        assertEquals("Europe/Berlin", nin.resetZone)
        assertEquals("9 answers (3x3 grid)", nin.targetFormat)
        assertEquals("Fill the 3x3 grid with matching games.", nin.description)

        // High-guess games (37 guesses), written counts (four mistakes), optimization goals, and max points
        assertEquals("37 guesses", WebsiteScraper.detectTargetFormat("Solve 32 Wordles at once in 37 guesses."))
        assertEquals("tries" to 37, WebsiteScraper.detectMetric("Solve 32 Wordles at once in 37 guesses."))
        assertEquals("4 mistakes", WebsiteScraper.detectTargetFormat("Group sixteen words into four categories with up to four mistakes."))
        assertEquals("Fewest guesses", WebsiteScraper.detectTargetFormat("Travle: A daily game, get between countries in as few guesses as possible!"))
        assertEquals("50,000 points", WebsiteScraper.detectTargetFormat("Score up to 50,000 points across five mystery photos."))
        assertEquals("Fastest time", WebsiteScraper.detectTargetFormat("Beat the fastest time on the stopwatch!"))
        assertEquals("60s target", WebsiteScraper.detectTargetFormat("Solve as many equations as you can in 60 seconds!"))
        // Audio clip duration ("1-second clip") must not trigger a stopwatch "time" metric
        assertEquals(null, WebsiteScraper.detectMetric("Guess the movie from a 1 second clip."))

        // Bare "world" or "city" does not falsely trigger Geography; non-word minigolf falls back to Custom Daily
        assertEquals("Word", WebsiteScraper.detectCategory("Bracket City — nested word clues known around the world", "theatlantic.com"))
        assertEquals("Gaming & RPG", WebsiteScraper.detectCategory("Gamedle - Guess the video game from its cover art", "gamedle.wtf"))
        assertEquals("Custom Daily", WebsiteScraper.detectCategory("putt.day: a daily hole of mini golf", "putt.day"))

        // Domain suffix stripped from og:site_name when <title> has the clean brand name, kept for putt.day
        val sq = WebsiteScraper.parseHtmlMetadata(
            """<html><head><title>Squaredle | Daily Word Search Puzzle</title><meta property="og:site_name" content="Squaredle.app" /><meta name="description" content="Squaredle is a daily word building game. Swipe letters in the grid to test your vocabulary every day." /></head></html>""",
            "https://squaredle.app/"
        )
        assertEquals("Squaredle", sq.name)
        assertEquals("Word", sq.category)
        assertTrue(sq.description.startsWith("Swipe letters in the grid"))
    }
}


