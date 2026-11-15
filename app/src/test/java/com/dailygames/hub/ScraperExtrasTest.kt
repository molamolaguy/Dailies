package com.dailygames.hub

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.3: JSON-LD, manifest, script clues, rendered pages and short descriptions. */
class ScraperExtrasTest {

    @Test
    fun jsonLdGivesNameDescriptionAndGenrePreferringTheGameEntry() {
        val html = """
            <html><head>
            <script type="application/ld+json">{"@context":"https://schema.org","@graph":[
              {"@type":"WebSite","name":"Puzzle Corp","description":"All our puzzles."},
              {"@type":["VideoGame","WebApplication"],"name":"Tilecraft","description":"Slide the tiles to rebuild today&#39;s picture.","genre":["Puzzle","Logic"]}
            ]}</script>
            <script type="application/ld+json">{ not json </script>
            </head><body></body></html>
        """.trimIndent()
        val info = WebsiteScraper.jsonLdInfo(WebsiteScraper.extractJsonLdBlocks(html))
        assertEquals("Tilecraft", info.name)
        assertEquals("Slide the tiles to rebuild today's picture.", info.description)
        assertTrue(info.genre.contains("Logic"))
        val meta = WebsiteScraper.parseHtmlMetadata(html, "https://tilecraft.example")
        assertEquals("Tilecraft", meta.name)
        assertEquals("Slide the tiles to rebuild today's picture.", meta.description)
        assertEquals("Math & Logic", meta.category)
    }

    @Test
    fun manifestNameWinsOverABareTitle() {
        val meta = WebsiteScraper.parseHtmlMetadata(
            "<html><head><title>Loading…</title></head></html>",
            "https://example.org/play",
            PageExtras(manifest = JSONObject().put("short_name", "Gridle").put("description", "Fill the grid with today's words."))
        )
        assertEquals("Gridle", meta.name)
        assertEquals("Fill the grid with today's words.", meta.description)
    }

    @Test
    fun scriptZoneHintsAreOnlyStrongSignals() {
        assertEquals("America/New_York", WebsiteScraper.scriptZoneHint("""x=new Intl.DateTimeFormat("en-US",{timeZone:"America/New_York",year:"numeric"})"""))
        assertEquals("utc", WebsiteScraper.scriptZoneHint("""o.toLocaleDateString("en",{timeZone:"UTC"})"""))
        assertEquals("utc", WebsiteScraper.scriptZoneHint("d.setUTCHours(0,0,0,0);var n=Math.floor((d-s)/864e5)"))
        assertNull(WebsiteScraper.scriptZoneHint("console.log(new Date().toISOString().slice(0,10))"))
        assertNull(WebsiteScraper.scriptZoneHint("var zones=['America/New_York','Europe/Paris']"))
    }

    @Test
    fun aScriptZoneIsUsedOnlyWhenThePageNamesNone() {
        val none = WebsiteScraper.parseHtmlMetadata("<title>G</title>", "https://g.example", PageExtras(scriptZone = "America/New_York"))
        assertEquals("America/New_York", none.resetZone)
        val written = WebsiteScraper.parseHtmlMetadata(
            "<title>G</title><body>New puzzle every day at 9 AM PT</body>", "https://g.example", PageExtras(scriptZone = "utc")
        )
        assertEquals("09:00", written.resetTime)
        assertEquals("America/Los_Angeles", written.resetZone)
    }

    @Test
    fun ownScriptsAreSameSiteAndNotTrackers() {
        val html = """
            <script src="https://www.googletagmanager.com/gtag/js?id=1"></script>
            <script defer src="/assets/index-abc123.js"></script>
            <script src="https://static.game.example/app.js"></script>
            <script src="https://cdn.jsdelivr.net/npm/lib.js"></script>
            <script src="https://other-site.com/x.js"></script>
        """.trimIndent()
        assertEquals(
            listOf("https://play.game.example/assets/index-abc123.js", "https://static.game.example/app.js"),
            WebsiteScraper.ownScriptUrls(html, "https://play.game.example/today")
        )
        assertEquals("https://play.game.example/site.webmanifest",
            WebsiteScraper.manifestUrl("""<link rel="manifest" href="/site.webmanifest">""", "https://play.game.example/today"))
    }

    @Test
    fun shortDescriptionsSayHowToPlayWithoutNameOrFiller() {
        assertEquals("Guess the hidden word in six tries.",
            WebsiteScraper.pickShortDescription(listOf("Play Wordle free online! Guess the hidden word in six tries. Share with friends."), "Wordle"))
        assertEquals("Find the mystery country using proximity hints.",
            WebsiteScraper.pickShortDescription(listOf("Globle is a daily game where you find the mystery country using proximity hints."), "Globle"))
        // A cookie notice is never the description.
        assertEquals("Connect the stars to draw today's constellation.",
            WebsiteScraper.pickShortDescription(listOf("We use cookies to improve your experience.", "Connect the stars to draw today's constellation."), "Stars"))
        // Long text is cut at a word, at most 90 characters.
        val long = WebsiteScraper.pickShortDescription(listOf("Guess the secret city from a satellite photo, a population hint, an elevation hint and a list of neighbouring cities and rivers."), "X")
        assertTrue(long, long.length <= 91 && long.endsWith("…"))
        assertEquals("", WebsiteScraper.pickShortDescription(emptyList(), "X"))
    }

    @Test
    fun noScoringClueMeansNoScoreType() {
        val meta = WebsiteScraper.parseHtmlMetadata("<title>Mystery</title><body>A daily game.</body>", "https://m.example")
        assertEquals("", meta.metricType)
        assertEquals(0, meta.par)
        assertEquals("time" to 60, WebsiteScraper.detectMetric("Beat the clock: how fast can you clear the board?"))
        assertEquals("tries" to 4, WebsiteScraper.detectMetric("You have 4 lives"))
    }

    @Test
    fun aRenderedPageFillsWhatAnEmptySinglePageAppLacks() {
        val rendered = JSONObject()
            .put("title", "Chronoquiz")
            .put("text", "Chronoquiz\nHow to play\nPut the six events in the order they happened.\nNew puzzle every day at midnight UTC.\nScore as many points as you can.")
            .put("meta", JSONObject().put("description", ""))
            .put("ld", JSONArray())
        val meta = WebsiteScraper.parseHtmlMetadata(
            """<html><head><title></title></head><body><div id="root"></div></body></html>""",
            "https://chronoquiz.example", PageExtras.fromRendered(rendered)
        )
        assertEquals("Chronoquiz", meta.name)
        assertEquals("Put the six events in the order they happened.", meta.description)
        assertEquals("00:00", meta.resetTime)
        assertEquals("utc", meta.resetZone)
        assertEquals("points", meta.metricType)
    }

    /** The JSON PageRenderer's script returned for a test single-page app in Chromium. */
    @Test
    fun theRendererOutputOfASinglePageAppIsParsed() {
        val rendered = JSONObject("""{"title":"Chronoquiz - Daily History Game","url":"https://chronoquiz.example/",
            "meta":{"og:description":"Play Chronoquiz free! Put six events in order."},
            "ld":["{\"@type\":\"VideoGame\",\"name\":\"Chronoquiz\"}"],
            "text":"Chronoquiz\nPlay\nHow to play\nRules\nDrag the six events into the order they happened.\nA new puzzle comes out every day at 9 AM ET."}""")
        val meta = WebsiteScraper.parseHtmlMetadata("<html><head><title></title></head><body><div id=\"root\"></div></body></html>",
            "https://chronoquiz.example/", PageExtras.fromRendered(rendered))
        assertEquals("Chronoquiz", meta.name)
        assertEquals("Put six events in order.", meta.description)
        assertEquals("09:00", meta.resetTime)
        assertEquals("America/New_York", meta.resetZone)
        assertEquals("Trivia", meta.category)
    }

    @Test
    fun aRenderedPageStandsInWhenThePlainFetchFails() {
        val res = WebsiteScraper.fetchAndScrape("127.0.0.1:1/blocked") {
            JSONObject().put("title", "Wordwheel").put("text", "How to play\nSpell as many words as you can from the wheel.").put("url", "https://wordwheel.example/")
        }
        assertTrue(res.getBoolean("ok"))
        assertTrue(res.getBoolean("rendered"))
        assertEquals("Wordwheel", res.getJSONObject("parsed").getString("name"))
        val none = WebsiteScraper.fetchAndScrape("127.0.0.1:1/blocked") { null }
        assertFalse(none.getBoolean("ok"))
    }
}
