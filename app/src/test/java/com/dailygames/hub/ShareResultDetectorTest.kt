package com.dailygames.hub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareResultDetectorTest {
    @Test
    fun strictDetection_matchesSharedFixtures() {
        val json = JSONObject(javaClass.classLoader!!.getResourceAsStream("share_cases.json")!!.bufferedReader().readText())
        val game = json.getJSONObject("game")
        val cases = json.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val got = ShareResultDetector.isStrictShareResult(
                c.getString("text"), c.optString("name", game.getString("name")), c.optString("url", game.getString("url"))
            )
            assertEquals(c.getString("text"), c.getBoolean("strict"), got)
        }
    }

    @Test
    fun normalizesCrlfInResultAndMentionChecks() {
        val crlf = "Wordle 1,234 3/6\r\n12345\r\n"
        assertTrue(ShareResultDetector.isStrictShareResult(crlf, "Wordle", "https://nytimes.com/games/wordle"))
        assertTrue(ShareResultDetector.mentionsGame("Wordle #12\r\n3/6", "Wordle", "https://nytimes.com/games/wordle"))
        assertTrue(ShareResultDetector.mentionsGameExplicitly("Wordle #12\r\n3/6", "Wordle", "https://nytimes.com/games/wordle"))
    }

    @Test
    fun handlesUnicodeIdnHostsAndRejectsNonNumericPorts() {
        // Unicode IDN domain is converted to punycode xn--... and matched cleanly.
        assertTrue(
            ShareResultDetector.mentionsGameExplicitly(
                "Solved today's puzzle at https://xn--bcher-kva.example/ in 4 tries",
                "Unknown",
                "https://bücher.example/"
            )
        )
        // Non-numeric port in malformed URL must not yield a valid host or crash.
        assertFalse(
            ShareResultDetector.mentionsGameExplicitly(
                "Played example.com today",
                "Other",
                "https://example.com:notaport/play"
            )
        )
    }

    @Test
    fun mentionsGameExplicitly_doesNotMatchBarePuzzleNumbers() {
        val otherGameClip = "Connections #12\n🟨🟨🟨🟨\n🟩🟩🟩🟩"
        // Legacy mentionsGame matches #12; mentionsGameExplicitly must not match Wordle!
        assertTrue(ShareResultDetector.mentionsGame(otherGameClip, "Wordle", "https://nytimes.com/games/wordle"))
        assertFalse(ShareResultDetector.mentionsGameExplicitly(otherGameClip, "Wordle", "https://nytimes.com/games/wordle"))
        assertTrue(ShareResultDetector.mentionsGameExplicitly(otherGameClip, "Connections", "https://nytimes.com/games/connections"))
    }
}

