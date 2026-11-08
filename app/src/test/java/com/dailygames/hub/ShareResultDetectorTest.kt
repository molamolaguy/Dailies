package com.dailygames.hub

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
