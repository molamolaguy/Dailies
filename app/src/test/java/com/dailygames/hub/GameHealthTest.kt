package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.0: Check links & icons, and the crash log's formatting. */
class GameHealthTest {
    @Test
    fun linkStatuses() {
        val u = "https://www.example.com/game"
        assertEquals("unreachable" to null, GameHealth.classify(u, null, null, false))
        assertEquals("dead", GameHealth.classify(u, 404, u, false).first)
        assertEquals("dead", GameHealth.classify(u, 410, u, false).first)
        assertEquals("error", GameHealth.classify(u, 500, u, false).first)
        assertEquals("blocked", GameHealth.classify(u, 403, u, false).first)
        assertEquals("blocked", GameHealth.classify(u, 200, u, true).first)
        assertEquals("ok", GameHealth.classify(u, 200, u, false).first)
        // www. or a path change on the same site isn't a move.
        assertEquals("ok", GameHealth.classify(u, 200, "https://example.com/play", false).first)
        assertEquals("moved" to "https://newsite.io/game", GameHealth.classify(u, 200, "https://newsite.io/game", false))
    }

    @Test
    fun parseGamesSkipsBadLinks() {
        val games = GameHealth.parseGames("""[{"id":"a","url":"https://a.com/","icon":""},{"id":"b","url":"javascript:alert(1)"},{"id":"c","url":"http://c.org"}]""")
        assertEquals(listOf("a", "c"), games.map { it.id })
        assertTrue(GameHealth.parseGames("not json").isEmpty())
    }

    @Test
    fun onlyBiggerIconsReplace() {
        assertEquals("https://a.com/apple-touch-icon.png", GameHealth.pickBetter(32, listOf("https://a.com/favicon.ico" to 16, "https://a.com/apple-touch-icon.png" to 180)))
        assertNull(GameHealth.pickBetter(192, listOf("https://a.com/apple-touch-icon.png" to 180)))
        assertNull(GameHealth.pickBetter(0, listOf("https://a.com/x.png" to 0)))
        assertEquals("https://a.com/x.png", GameHealth.pickBetter(0, listOf("https://a.com/x.png" to 64)))
    }

    @Test
    fun crashDescriptionHasTypeMessageAndCause() {
        val e = IllegalStateException("outer", RuntimeException("inner"))
        val d = CrashLog.describe(e)
        assertTrue(d.startsWith("java.lang.IllegalStateException: outer"))
        assertTrue(d.contains("Caused by: java.lang.RuntimeException: inner"))
        assertTrue(d.contains("\n    at "))
    }
}
