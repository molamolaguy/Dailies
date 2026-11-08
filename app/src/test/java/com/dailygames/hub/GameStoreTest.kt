package com.dailygames.hub

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class GameStoreTest {

    @Test
    fun inMemoryStorage_readsAndWritesKeys() {
        val storage = InMemoryGameStorage(mapOf("initial" to "val"))
        assertTrue(storage.contains("initial"))
        assertEquals("val", storage.getString("initial"))
        assertFalse(storage.contains("missing"))
        assertNull(storage.getString("missing"))

        storage.putString("missing", "added")
        assertTrue(storage.contains("missing"))
        assertEquals("added", storage.getString("missing"))
    }

    @Test
    fun repository_loadReturnsEmptyArrayWhenBlankOrInvalidJson() {
        val storage = InMemoryGameStorage()
        val repo = GameRepository(storage)
        assertEquals(0, repo.load().length())

        storage.putString(GameStore.KEY_GAMES, "{not-an-array")
        assertEquals(0, repo.load().length())
    }

    @Test
    fun repository_syncAnchorsNewGamesAndReanchorsWhenScheduleChanges() {
        // 2026-03-15 02:00:00 UTC
        val fixedNow = 1773540000000L
        val utcZone = TimeZone.getTimeZone("UTC")
        val storage = InMemoryGameStorage(
            mapOf(GameStore.NOTIFIED_PREFIX + "existing_id" to "2026-03-14")
        )
        val repo = GameRepository(storage, { fixedNow }, utcZone)

        val initialPayload = """
            [
              {"id": "new_utc", "name": "Worldle", "resetTime": "00:00", "resetZone": "utc"},
              {"id": "existing_id", "name": "Wordle", "resetTime": "00:00", "resetZone": "utc"},
              {"id": "", "name": "BlankId"}
            ]
        """.trimIndent()

        repo.sync(initialPayload)
        assertEquals(3, repo.load().length())
        assertEquals("2026-03-15", repo.lastNotifiedKey("new_utc"))
        // Existing game whose schedule was not previously stored or didn't change keeps its key
        assertEquals("2026-03-14", repo.lastNotifiedKey("existing_id"))

        // Now change existing_id's resetTime from 00:00 to 04:00 UTC -> at 02:00 UTC its current cycle is 2026-03-14,
        // and changing new_utc's resetTime from 00:00 to 04:00 UTC re-anchors it to 2026-03-14 (K8).
        val updatedSchedulePayload = """
            [
              {"id": "new_utc", "name": "Worldle", "resetTime": "04:00", "resetZone": "utc"},
              {"id": "existing_id", "name": "Wordle", "resetTime": "00:00", "resetZone": "utc"}
            ]
        """.trimIndent()
        repo.sync(updatedSchedulePayload)
        assertEquals("2026-03-14", repo.lastNotifiedKey("new_utc"))

        repo.setNotifiedKey("existing_id", "2026-03-15")
        assertEquals("2026-03-15", repo.lastNotifiedKey("existing_id"))

        repo.saveGames(JSONArray())
        assertEquals(0, repo.load().length())
    }

    @Test
    fun repository_syncIgnoresMalformedJson() {
        val storage = InMemoryGameStorage()
        val repo = GameRepository(storage)
        repo.sync("not valid json")
        assertFalse(storage.contains(GameStore.KEY_GAMES))
    }

    @Test
    fun cycleKey_shiftsByResetOffsetInUtcAndLocalTimeZones() {
        // 2026-03-15 03:30:00 UTC
        val nowMs = 1773545400000L
        val utcZone = TimeZone.getTimeZone("UTC")

        // Reset at 00:00 UTC -> already in 2026-03-15 cycle
        assertEquals("2026-03-15", GameStore.cycleKey("00:00", "utc", nowMs, utcZone))

        // Reset at 04:00 UTC -> 03:30 UTC is still in 2026-03-14 cycle!
        assertEquals("2026-03-14", GameStore.cycleKey("04:00", "utc", nowMs, utcZone))

        // Malformed resetTime falls back to 00:00 safely
        assertEquals("2026-03-15", GameStore.cycleKey("invalid", "local", nowMs, utcZone))
    }

    @Test
    fun msUntilNextReset_nextResetDelayMs_and_formatLocalResetTime_computeExpectedValues() {
        // 2026-03-15 12:00:00 UTC
        val nowMs = 1773576000000L
        val utcZone = TimeZone.getTimeZone("UTC")
        val waitMs = GameStore.msUntilNextReset("18:00", "utc", nowMs, utcZone)
        assertEquals(6L * 3600_000L, waitMs)

        val games = JSONArray(
            """
            [
              {"id": "g1", "resetTime": "18:00", "resetZone": "utc", "notifyEnabled": true},
              {"id": "g2", "resetTime": "14:00", "resetZone": "utc", "notifyEnabled": true},
              {"id": "g3", "resetTime": "13:00", "resetZone": "utc", "notifyEnabled": false}
            ]
            """.trimIndent()
        )
        assertEquals(2L * 3600_000L, GameStore.nextResetDelayMs(games, nowMs, utcZone))

        val mutedOnly = JSONArray("""[{"id": "g3", "resetTime": "13:00", "resetZone": "utc", "notifyEnabled": false}]""")
        assertNull(GameStore.nextResetDelayMs(mutedOnly, nowMs, utcZone))

        val localLabel = GameStore.formatLocalResetTime("00:00", "utc", utcZone)
        assertEquals("12:00 AM", localLabel)
        val pmLabel = GameStore.formatLocalResetTime("15:30", "local", utcZone)
        assertEquals("3:30 PM", pmLabel)
    }
}
