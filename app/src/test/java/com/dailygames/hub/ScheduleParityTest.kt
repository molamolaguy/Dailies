package com.dailygames.hub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/**
 * Runs the shared schedule fixtures (also used by app/src/test/js/hub-core.test.js) against the
 * Kotlin implementation, so the notification worker and the hub UI always agree on reset timing.
 */
class ScheduleParityTest {
    private val cases: JSONObject by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("schedule_cases.json")!!.bufferedReader().readText()
        JSONObject(text)
    }

    @Test
    fun cycleKey_matchesSharedFixtures() {
        val arr = cases.getJSONArray("cycleKey")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val key = GameStore.cycleKey(
                c.getString("resetTime"), c.getString("resetZone"),
                Instant.parse(c.getString("now")).toEpochMilli(), TimeZone.getTimeZone(c.getString("tz"))
            )
            assertEquals(c.getString("note"), c.getString("expect"), key)
        }
    }

    @Test
    fun msUntilNextReset_matchesSharedFixtures() {
        val arr = cases.getJSONArray("msUntilNextReset")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val ms = GameStore.msUntilNextReset(
                c.getString("resetTime"), c.getString("resetZone"),
                Instant.parse(c.getString("now")).toEpochMilli(), TimeZone.getTimeZone(c.getString("tz"))
            )
            assertEquals(c.getString("note"), c.getLong("expect"), ms)
        }
    }
}
