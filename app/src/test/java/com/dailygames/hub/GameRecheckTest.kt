package com.dailygames.hub

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRecheckTest {
    @Test
    fun targetsAreWebGamesOnceEach() {
        val games = JSONArray()
            .put(JSONObject().put("id", "a").put("url", "https://a.example/"))
            .put(JSONObject().put("id", "b").put("url", "file:///sdcard/x.html"))
            .put(JSONObject().put("id", "").put("url", "https://c.example/"))
            .put(JSONObject().put("id", "a").put("url", "https://a2.example/"))
            .put(JSONObject().put("id", "d").put("url", " HTTP://d.example/play "))
        assertEquals(listOf("a" to "https://a.example/", "d" to "HTTP://d.example/play"), GameRecheck.targets(games))
        assertEquals(1, GameRecheck.targets(games, max = 1).size)
    }

    @Test
    fun collectKeepsReadablePagesOnlyAndStopsAtTheBudget() {
        val pages = mapOf(
            "https://ok.example/" to JSONObject().put("ok", true).put("finalUrl", "https://ok.example/home")
                .put("parsed", JSONObject().put("name", "Ok Game").put("timeFound", true)),
            "https://blocked.example/" to JSONObject().put("ok", false).put("error", "bot_challenge")
                .put("parsed", JSONObject().put("name", "Just a moment")),
        )
        val out = GameRecheck.collect(
            listOf("1" to "https://ok.example/", "2" to "https://blocked.example/", "3" to "https://throws.example/"),
            { url -> pages[url] ?: throw java.io.IOException("offline") }
        )
        assertEquals(1, out.length())
        val r = out.getJSONObject(0)
        assertEquals("1", r.getString("id"))
        assertEquals("https://ok.example/", r.getString("url"))
        assertEquals("https://ok.example/home", r.getString("finalUrl"))
        assertEquals("Ok Game", r.getJSONObject("parsed").getString("name"))

        // Out of time after the first page: the rest wait for the next pass.
        var now = 0L
        val timed = GameRecheck.collect(
            listOf("1" to "https://ok.example/", "4" to "https://ok.example/"),
            { url -> now += 5_000; pages.getValue(url) },
            budgetMs = 4_000,
            clock = { now }
        )
        assertEquals(1, timed.length())
    }

    @Test
    fun weeklyPasses() {
        val week = GameRecheck.INTERVAL_MS
        assertTrue(GameRecheck.isDue(0L, 1_000L))
        assertFalse(GameRecheck.isDue(1_000_000L, 1_000_000L + week / 2))
        assertTrue(GameRecheck.isDue(1_000_000L, 1_000_000L + week))
        // The clock went back: due rather than never.
        assertTrue(GameRecheck.isDue(1_000_000L, 10L))
    }

    @Test
    fun scraperSaysWhetherThePageStatesItsResetTime() {
        val stated = WebsiteScraper.parseHtmlMetadata(
            "<html><head><title>Clockle</title></head><body>A new puzzle every day at 5:00 UTC.</body></html>",
            "https://clockle.example/"
        )
        assertTrue(stated.timeFound)
        assertTrue(stated.zoneFound)
        assertEquals(true, stated.toJson().getBoolean("timeFound"))
        val silent = WebsiteScraper.parseHtmlMetadata("<html><head><title>Quietle</title></head><body>Guess the word.</body></html>", "https://quietle.example/")
        assertFalse(silent.timeFound)
        assertFalse(silent.toJson().getBoolean("zoneFound"))
    }

    @Test
    fun targetsSkipArchivedGamesAndCollectStopsWhenCancelled() {
        val games = JSONArray()
            .put(JSONObject().put("id", "active").put("url", "https://active.example/"))
            .put(JSONObject().put("id", "arch").put("url", "https://arch.example/").put("archived", true))
        assertEquals(listOf("active" to "https://active.example/"), GameRecheck.targets(games))

        var active = true
        var scraped = 0
        val out = GameRecheck.collect(
            listOf("1" to "https://a.example/", "2" to "https://b.example/"),
            scrape = {
                scraped++
                active = false
                JSONObject().put("ok", true).put("parsed", JSONObject().put("name", "A"))
            },
            isActive = { active }
        )
        assertEquals(1, scraped)
        assertEquals(1, out.length())
    }
}

