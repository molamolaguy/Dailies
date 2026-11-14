package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class DailyRefreshProcessorTest {

    @Test
    fun formatRefreshNotification_handlesEmojiAndFaviconUrls() {
        val (title1, body1) = Notifier.formatRefreshNotification("🧩", "Connections")
        assertEquals("Connections has refreshed", title1)
        assertEquals(Notifier.DEFAULT_REFRESH_BODY, body1)

        val (title2, _) = Notifier.formatRefreshNotification("https://www.nytimes.com/favicon.ico", "Wordle")
        assertEquals("Wordle has refreshed", title2)

        val (title3, _) = Notifier.formatRefreshNotification("data:image/png;base64,AAA", "")
        assertEquals("Your daily game has refreshed", title3)

        val (title4, _) = Notifier.formatRefreshNotification(null, null)
        assertEquals("Your daily game has refreshed", title4)
    }

    @Test
    fun processRefresh_dispatchesOnlyForGamesWithNewCycleAndAlertsEnabled() {
        // Day 1: 2026-03-14 12:00:00 UTC
        val day1Ms = 1773489600000L
        // Day 2: 2026-03-15 12:00:00 UTC
        val day2Ms = 1773576000000L

        val storage = InMemoryGameStorage()
        val repo = GameRepository(storage, clock = { day1Ms }, localTimeZone = TimeZone.getTimeZone("UTC"))

        val gamesJson = """
            [
              {"id": "g1", "name": "Wordle", "icon": "🟩", "resetTime": "00:00", "resetZone": "utc", "notifyEnabled": true},
              {"id": "g2", "name": "SilentGame", "icon": "🔇", "resetTime": "00:00", "resetZone": "utc", "notifyEnabled": false},
              {"id": "g3", "name": "FaviconGame", "icon": "https://example.com/icon.png", "resetTime": "00:00", "resetZone": "utc", "notifyEnabled": true}
            ]
        """.trimIndent()
        repo.sync(gamesJson)

        // Also add an unanchored game directly to storage to test null lastKey path
        val unanchoredJson = """
            [
              {"id": "g1", "name": "Wordle", "icon": "🟩", "resetTime": "00:00", "resetZone": "utc", "notifyEnabled": true},
              {"id": "g2", "name": "SilentGame", "icon": "🔇", "resetTime": "00:00", "resetZone": "utc", "notifyEnabled": false},
              {"id": "g3", "name": "FaviconGame", "icon": "https://example.com/icon.png", "resetTime": "00:00", "resetZone": "utc", "notifyEnabled": true},
              {"id": "g4_unanchored", "name": "NewGame", "icon": "✨", "resetTime": "00:00", "resetZone": "utc", "notifyEnabled": true}
            ]
        """.trimIndent()
        storage.putString(GameStore.KEY_GAMES, unanchoredJson)

        val dispatched = mutableListOf<Triple<String, String, String>>()
        val processor = DailyRefreshProcessor(
            repository = repo,
            dispatcher = { id, title, body -> dispatched.add(Triple(id, title, body)) },
            localTimeZone = TimeZone.getTimeZone("UTC")
        )

        // Running on Day 1 should anchor g4_unanchored and send 0 notifications
        val countDay1 = processor.processRefresh(day1Ms)
        assertEquals(0, countDay1)
        assertEquals(0, dispatched.size)
        assertEquals("2026-03-14", repo.lastNotifiedKey("g4_unanchored"))

        // Running on Day 2 should notify g1, g3, and g4_unanchored (3 notifications), skipping g2 (notifyEnabled=false)
        val countDay2 = processor.processRefresh(day2Ms)
        assertEquals(3, countDay2)
        assertEquals(3, dispatched.size)
        assertEquals("g1", dispatched[0].first)
        assertEquals("Wordle has refreshed", dispatched[0].second)
        assertEquals("g3", dispatched[1].first)
        assertEquals("FaviconGame has refreshed", dispatched[1].second)
        assertEquals("g4_unanchored", dispatched[2].first)

        // Running again on Day 2 should send 0 notifications
        val countDay2Repeat = processor.processRefresh(day2Ms)
        assertEquals(0, countDay2Repeat)

        // Verify default constructor & default nowMs parameter
        val defaultProc = DailyRefreshProcessor(repo, { _, _, _ -> })
        defaultProc.processRefresh()
    }

    @Test
    fun aResetThatWasNotAnnouncedIsSeenWhenTheScheduleIsReArmed() {
        // 2026-11-13 23:59:59 and 2026-11-14 00:00:00.5 in New York (a midnight game).
        val ny = TimeZone.getTimeZone("America/New_York")
        val beforeMidnight = 1794632399000L
        val afterMidnight = 1794632400500L
        var now = beforeMidnight
        val storage = InMemoryGameStorage()
        val repo = GameRepository(storage, clock = { now }, localTimeZone = ny)
        val games = """[{"id":"w","name":"Wordle","resetTime":"00:00","resetZone":"local","notifyEnabled":true}]"""
        repo.sync(games)
        assertEquals(false, repo.hasUnannouncedReset())
        // Midnight passes and the hub saves its list (its own rollover) before the reset alarm fires.
        now = afterMidnight
        repo.sync(games)
        // The save must not swallow the reset: re-arming sees it and posts it first.
        assertEquals(true, repo.hasUnannouncedReset())
        val dispatched = mutableListOf<String>()
        DailyRefreshProcessor(repo, { id, _, _ -> dispatched += id }, ny).processRefresh(afterMidnight)
        assertEquals(listOf("w"), dispatched)
        assertEquals(false, repo.hasUnannouncedReset())
        // Alerts off: nothing to announce.
        now = afterMidnight + 86_400_000L
        repo.sync("""[{"id":"w","name":"Wordle","resetTime":"00:00","resetZone":"local","notifyEnabled":false}]""")
        assertEquals(false, repo.hasUnannouncedReset())
    }
}
