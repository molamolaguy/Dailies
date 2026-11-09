package com.dailygames.hub

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Date
import java.util.TimeZone

/** Tests for the 1.1.0 features: streak reminders, alert-channel migration, auto-backup, update check, theme. */
class V110FeaturesTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private val utc = TimeZone.getTimeZone("UTC")
    private val planner = StreakReminderPlanner(utc)
    private val hour = 3_600_000L

    private fun games(vararg g: String) = JSONArray("[${g.joinToString(",")}]")
    private fun game(id: String, streak: Int, reset: String = "00:00", logged: String = "", notify: Boolean = true) =
        """{"id":"$id","name":"${id.replaceFirstChar { it.uppercase() }}","resetTime":"$reset","resetZone":"utc","streak":$streak,"loggedCycle":"$logged","notifyEnabled":$notify}"""

    @Test
    fun remindsOnlyStreakGamesNotLoggedInsideTheWindow() {
        val now = ms("2026-11-08T23:15:00Z") // 45 min before the 00:00 UTC reset; cycle 2026-11-08
        val list = games(game("wordle", 5), game("framed", 0), game("nerdle", 3, logged = "2026-11-08"),
            game("muted", 4, notify = false), game("later", 2, reset = "06:00"))
        val due = planner.due(list, now, hour) { null }
        assertEquals(listOf("wordle"), due.map { it.id })
        assertEquals("2026-11-08", due[0].cycleKey)
        // Already reminded this cycle: nothing.
        assertTrue(planner.due(list, now, hour) { if (it == "wordle") "2026-11-08" else null }.isEmpty())
        // Off: nothing.
        assertTrue(planner.due(list, now, 0) { null }.isEmpty())
    }

    @Test
    fun nextCheckIsTheEarliestWindowOrNowWhenOneIsOpen() {
        val list = games(game("wordle", 5), game("later", 2, reset = "06:00"))
        // 20:00: wordle's 1 h window opens at 23:00.
        assertEquals(ms("2026-11-08T23:00:00Z"), planner.nextCheck(list, ms("2026-11-08T20:00:00Z"), hour) { null })
        // 23:30, window open and not reminded yet: check now.
        val now = ms("2026-11-08T23:30:00Z")
        assertEquals(now, planner.nextCheck(list, now, hour) { null })
        // Reminded: the next one is "later" at 05:00.
        assertEquals(ms("2026-11-09T05:00:00Z"), planner.nextCheck(list, now, hour) { if (it == "wordle") "2026-11-08" else null })
        assertNull(planner.nextCheck(games(game("none", 0)), now, hour) { null })
        assertNull(planner.nextCheck(list, now, 0) { null })
    }

    @Test
    fun reminderTextNamesTheGamesAndLeadTime() {
        val now = ms("2026-11-08T23:15:00Z")
        val one = planner.due(games(game("wordle", 5)), now, hour) { null }
        val (t1, b1) = planner.message(one, hour)
        assertEquals("Keep your Wordle streak", t1)
        assertTrue(b1, b1.contains("5-day streak") && b1.contains("1 hr"))
        val two = planner.due(games(game("wordle", 5), game("nerdle", 2)), now, 2 * hour) { null }
        assertEquals("Keep your 2 streaks going", planner.message(two, 2 * hour).first)
        assertEquals("30 min", StreakReminderPlanner.formatLead(30 * 60_000L))
        assertEquals("1 hr 30 min", StreakReminderPlanner.formatLead(90 * 60_000L))
    }

    @Test
    fun alertChannelMigrationKeepsUserChoices() {
        assertEquals(Notifier.ChannelMigration.CREATE_NEW, Notifier.channelMigration(false, false, false))
        assertEquals(Notifier.ChannelMigration.REPLACE_LEGACY, Notifier.channelMigration(true, false, false))
        assertEquals(Notifier.ChannelMigration.KEEP_LEGACY, Notifier.channelMigration(true, true, false))
        assertEquals(Notifier.ChannelMigration.KEEP_LEGACY, Notifier.channelMigration(true, false, true))
    }

    @Test
    fun autoBackupPayloadMatchesSaveBackupAndPrunesOldFiles() {
        val state = """{"schemaVersion":5,"settings":{"timeFormat":"24h"},"games":[{"id":"w","name":"Wordle"}],"historyLogs":[{"gameId":"w"}],"retainedGameStats":{}}"""
        val out = JSONObject(AutoBackup.payload(state, "1.1.0", Date(ms("2026-11-09T10:00:00Z")))!!)
        assertEquals("Dailies", out.getString("app"))
        assertEquals("1.1.0", out.getString("version"))
        assertEquals(5, out.getInt("schemaVersion"))
        assertEquals("Wordle", out.getJSONArray("games").getJSONObject(0).getString("name"))
        assertEquals(1, out.getJSONArray("historyLogs").length())
        assertNull(AutoBackup.payload("", "1.1.0", Date()))
        assertNull(AutoBackup.payload("{\"settings\":{}}", "1.1.0", Date()))

        val names = (1..7).map { "dailies_autobackup_2026-11-0${it}_1200.json" } + listOf("dailies_backup_2026-11-01.json", "notes.txt")
        assertEquals(listOf("dailies_autobackup_2026-11-02_1200.json", "dailies_autobackup_2026-11-01_1200.json"), AutoBackup.namesToPrune(names))
        assertTrue(AutoBackup.fileName(Date()).startsWith(AutoBackup.FILE_PREFIX))

        val week = AutoBackup.INTERVAL_MS
        val now = ms("2026-11-09T10:00:00Z")
        assertTrue(AutoBackup.isDue(0, now))
        assertFalse(AutoBackup.isDue(now - week / 2, now))
        assertTrue(AutoBackup.isDue(now - week, now))
        assertTrue("a clock set back must not stop backups for good", AutoBackup.isDue(now + week, now))
    }

    @Test
    fun updateCheckComparesVersionsAndIgnoresPrereleases() {
        assertTrue(UpdateCheck.isNewer("v1.2.0", "1.1.0"))
        assertTrue(UpdateCheck.isNewer("1.1.1", "1.1"))
        assertFalse(UpdateCheck.isNewer("v1.1.0", "1.1.0"))
        assertFalse(UpdateCheck.isNewer("1.0.9", "1.1.0"))
        assertFalse(UpdateCheck.isNewer("nightly", "1.1.0"))
        assertNull(UpdateCheck.parse("1.x"))
        assertEquals(listOf(2, 0, 1), UpdateCheck.parse("v2.0.1-beta"))

        val ok = JSONObject(UpdateCheck.resultJson("""{"tag_name":"v1.2.0","draft":false,"prerelease":false}""", 200, "1.1.0"))
        assertTrue(ok.getBoolean("ok")); assertTrue(ok.getBoolean("newer")); assertEquals("1.2.0", ok.getString("latest"))
        val pre = JSONObject(UpdateCheck.resultJson("""{"tag_name":"v1.3.0","prerelease":true}""", 200, "1.1.0"))
        assertFalse(pre.getBoolean("newer"))
        assertFalse(JSONObject(UpdateCheck.resultJson(null, 0, "1.1.0")).getBoolean("ok"))
        assertFalse(JSONObject(UpdateCheck.resultJson("rate limited", 403, "1.1.0")).getBoolean("ok"))
    }

    @Test
    fun themeModeResolution() {
        assertTrue(ThemePrefs.isLight("light", systemDark = true))
        assertFalse(ThemePrefs.isLight("dark", systemDark = false))
        assertTrue(ThemePrefs.isLight("system", systemDark = false))
        assertFalse(ThemePrefs.isLight("system", systemDark = true))
        assertNotNull(ThemePrefs.DEFAULT_LIGHT_SURFACE)
    }
}
