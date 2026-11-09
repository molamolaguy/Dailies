package com.dailygames.hub

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameListCodecTest {
    private val games = """[
        {"name":"Wordle","url":"https://www.nytimes.com/games/wordle/index.html","resetTime":"00:00","resetZone":"local"},
        {"name":"Globle","url":"https://globle-game.com","resetTime":"00:00","resetZone":"utc"},
        {"name":"Local thing","url":"file:///etc/passwd","resetTime":"00:00","resetZone":"local"},
        {"name":"FE Guess","url":"https://www.feguess.com","resetTime":"19:00","resetZone":"America/New_York"}
    ]"""

    @Test
    fun roundTripsAListAndDropsNonWebLinks() {
        val link = GameListCodec.encode(games)
        assertNotNull(link)
        assertTrue(link!!, link.startsWith("dailies://add?v=1&d="))
        val back = JSONArray(GameListCodec.decode("Here are my dailies: $link enjoy!"))
        assertEquals(3, back.length())
        assertEquals("FE Guess", back.getJSONObject(2).getString("name"))
        assertEquals("America/New_York", back.getJSONObject(2).getString("resetZone"))
        assertEquals("https://globle-game.com", back.getJSONObject(1).getString("url"))
    }

    @Test
    fun twentyGamesStayShortEnoughForAQrCode() {
        val many = JSONArray()
        for (i in 1..20) many.put(org.json.JSONObject().put("name", "Daily game $i").put("url", "https://example$i.com/daily").put("resetTime", "00:00").put("resetZone", "utc"))
        val link = GameListCodec.encode(many.toString())!!
        assertTrue("link is ${link.length} chars", link.length < 900)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(GameListCodec.decode("dailies://add?v=1&d=notreallydeflate"))
        assertNull(GameListCodec.decode("no link here"))
        assertNull(GameListCodec.decode(null))
        assertNull(GameListCodec.encode("[]"))
        assertNull(GameListCodec.encode("not json"))
    }

    @Test
    fun base64UrlMatchesTheJdk() {
        val rnd = java.util.Random(7)
        for (len in 0..40) {
            val data = ByteArray(len).also { rnd.nextBytes(it) }
            val mine = GameListCodec.b64UrlEncode(data)
            assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data), mine)
            assertTrue(data.contentEquals(GameListCodec.b64UrlDecode(mine)))
        }
        assertNull(GameListCodec.b64UrlDecode("ab*c"))
    }
}
