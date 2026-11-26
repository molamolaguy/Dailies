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
    fun aGameCanSetItsOwnReminderTime() {
        val now = ms("2026-11-08T22:30:00Z") // 90 min before the 00:00 UTC reset
        fun withLead(id: String, minutes: Int) = game(id, 4).dropLast(1) + ",\"reminderMinutes\":$minutes}"
        val list = games(withLead("own2h", 120), withLead("off", 0), withLead("asSettings", -1), game("plain", 2))
        // App-wide 1 hr: only the game with its own 2 hr window is due yet.
        assertEquals(listOf("own2h"), planner.due(list, now, hour) { null }.map { it.id })
        // App-wide off: a game's own time still reminds; "off" never does.
        assertEquals(listOf("own2h"), planner.due(list, now, 0) { null }.map { it.id })
        assertEquals(listOf("own2h", "asSettings", "plain"), planner.due(list, now, 3 * hour) { null }.map { it.id }.sortedBy { listOf("own2h", "asSettings", "plain").indexOf(it) })
        // Next check with the setting off: 22:00 for the 2 hr game (already passed, so now).
        assertEquals(now, planner.nextCheck(list, now, 0) { null })
        assertNull(planner.nextCheck(games(withLead("off", 0)), now, hour) { null })
        assertTrue(planner.message(planner.due(list, now, 0) { null }, 0).second.contains("1 hr 30 min"))
        assertTrue(planner.message(planner.due(list, ms("2026-11-08T22:00:00Z"), 0) { null }, 0).second.contains("2 hr"))
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
    fun skipsBrokenStreaksAndAllowsExplicitReminderWhenMuted() {
        val now = ms("2026-11-08T23:15:00Z") // cycle 2026-11-08, yesterday 2026-11-07
        val broken = game("broken", 5, logged = "2026-11-06")
        val alive = game("alive", 5, logged = "2026-11-07")
        val mutedOwn = game("mutedOwn", 4, logged = "2026-11-07", notify = false).dropLast(1) + ",\"reminderMinutes\":120}"
        val mutedDefault = game("mutedDefault", 4, logged = "2026-11-07", notify = false)
        val list = games(broken, alive, mutedOwn, mutedDefault)

        val due = planner.due(list, now, hour) { null }.map { it.id }
        assertEquals(listOf("alive", "mutedOwn"), due)

        // Broken streak alone produces no nextCheck alarm.
        assertNull(planner.nextCheck(games(broken), now, hour) { null })
        // Muted game with explicit reminderMinutes still gets a nextCheck alarm.
        assertEquals(now, planner.nextCheck(games(mutedOwn), now, hour) { null })
        // Muted game inheriting default gets no alarm.
        assertNull(planner.nextCheck(games(mutedDefault), now, hour) { null })

        // When index.html sends lastLoggedCycle alongside empty today's loggedCycle, broken streak is still caught.
        val brokenViaLastLogged = """{"id":"broken2","name":"Broken2","resetTime":"00:00","resetZone":"utc","streak":5,"loggedCycle":"","lastLoggedCycle":"2026-11-06","notifyEnabled":true}"""
        val aliveViaLastLogged = """{"id":"alive2","name":"Alive2","resetTime":"00:00","resetZone":"utc","streak":5,"loggedCycle":"","lastLoggedCycle":"2026-11-07","notifyEnabled":true}"""
        assertEquals(
            listOf("alive2"),
            planner.due(games(brokenViaLastLogged, aliveViaLastLogged), now, hour) { null }.map { it.id }
        )
        assertNull(planner.nextCheck(games(brokenViaLastLogged), now, hour) { null })
    }

    @Test
    fun nextCheckHandlesSpringForwardDstAndSlackWindowWithoutDoubleWake() {
        val ny = TimeZone.getTimeZone("America/New_York")
        val nyPlanner = StreakReminderPlanner(ny)
        // 2026-03-08 is US spring-forward day (23-hour day in America/New_York).
        // Reset is 00:00 America/New_York. On 2026-03-07 23:30 EST (2026-03-08T04:30:00Z),
        // today's cycle (2026-03-07) resets in 30 min (at 2026-03-08T05:00:00Z).
        // Tomorrow's cycle (2026-03-08) resets at 2026-03-09T00:00:00 EDT = 2026-03-09T04:00:00Z (23h later).
        // With 1 hr lead, tomorrow's window opens at 2026-03-09T03:00:00Z.
        val g = """{"id":"w","name":"Wordle","resetTime":"00:00","resetZone":"America/New_York","streak":5,"loggedCycle":"2026-03-07","notifyEnabled":true}"""
        val now = ms("2026-03-08T04:30:00Z")
        assertEquals(ms("2026-03-09T03:00:00Z"), nyPlanner.nextCheck(games(g), now, hour) { null })

        // Slack window: 60 min 30 sec before reset (inside lead + SLACK_MS), already reminded this cycle.
        val slackNow = ms("2026-11-08T22:59:30Z")
        val w = games(game("wordle", 5, logged = "2026-11-07"))
        // Must schedule tomorrow's window (2026-11-09T23:00:00Z), NOT 2026-11-08T23:00:00Z (30s later).
        assertEquals(ms("2026-11-09T23:00:00Z"), planner.nextCheck(w, slackNow, hour) { "2026-11-08" })
    }

    @Test
    fun reminderTextNamesTheGamesAndAccurateTimeRemaining() {
        val now = ms("2026-11-08T23:15:00Z") // 45 min until 00:00 UTC reset
        val one = planner.due(games(game("wordle", 5)), now, hour) { null }
        val (t1, b1) = planner.message(one, hour)
        assertEquals("Keep your Wordle streak", t1)
        assertTrue(b1, b1.contains("5-day streak") && b1.contains("45 min"))
        val two = planner.due(games(game("wordle", 5), game("nerdle", 2)), now, 2 * hour) { null }
        assertEquals("Keep your 2 streaks going", planner.message(two, 2 * hour).first)
        // Mixed reset times must not claim a 50-min game resets "within 6 hr".
        val mixedSoon = StreakReminderPlanner.Candidate("a", "Soon", 4, 50 * 60_000L, "2026-11-08", 6 * hour)
        val mixedLater = StreakReminderPlanner.Candidate("b", "Later", 3, 5 * hour, "2026-11-08", 6 * hour)
        val (_, bMixed) = planner.message(listOf(mixedSoon, mixedLater), 6 * hour)
        assertTrue(bMixed, bMixed.contains("50 min") && bMixed.contains("5 hr") && !bMixed.contains("within 6 hr"))
        assertEquals("30 min", StreakReminderPlanner.formatLead(30 * 60_000L))
        assertEquals("1 hr 30 min", StreakReminderPlanner.formatLead(90 * 60_000L))
    }

    @Test
    fun streakReminderShadeItemsMergeAndReconcileLoggedGames() {
        val now = ms("2026-11-08T23:15:00Z")
        val list = games(
            game("wordle", 5, logged = "2026-11-08"), // just logged!
            game("nerdle", 3, logged = "2026-11-07"), // still open
            game("globle", 4, logged = "2026-11-07")  // newly due
        )
        val stillOpen = planner.openDue(list, now, hour)
        val inShade = listOf(
            StreakReminderPlanner.Candidate("wordle", "Wordle", 5, 45 * 60_000L, "2026-11-08", hour),
            StreakReminderPlanner.Candidate("nerdle", "Nerdle", 3, 45 * 60_000L, "2026-11-08", hour)
        )
        val fresh = listOf(
            StreakReminderPlanner.Candidate("globle", "Globle", 4, 45 * 60_000L, "2026-11-08", hour)
        )
        val encoded = StreakReminders.encodeItems(inShade)
        assertEquals(listOf("wordle", "nerdle"), StreakReminders.decodeItems(encoded).map { it.id })
        val merged = StreakReminders.mergeShadeItems(inShade, fresh, stillOpen)
        assertEquals(listOf("nerdle", "globle"), merged.map { it.id })
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

        val names = (1..7).map { "dailies_autobackup_2026-11-0${it}_120000.json" } +
            listOf("dailies_autobackup_2026-10-30_1200.json", "dailies_backup_2026-11-01.json", "notes.txt")
        assertEquals(
            listOf(
                "dailies_autobackup_2026-11-02_120000.json",
                "dailies_autobackup_2026-11-01_120000.json",
                "dailies_autobackup_2026-10-30_1200.json"
            ),
            AutoBackup.namesToPrune(names)
        )
        // Duplicate display names with distinct docIds are still pruned cleanly.
        val entries = (1..6).map { "dailies_autobackup_2026-11-01_120000.json" to "doc-$it" }
        assertEquals(1, AutoBackup.entriesToPrune(entries).size)
        assertTrue(Regex("""^dailies_autobackup_\d{4}-\d{2}-\d{2}_\d{6}\.json$""").matches(AutoBackup.fileName(Date())))

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

        assertTrue(UpdateInstaller.isAllowedHost("github.com"))
        assertTrue(UpdateInstaller.isAllowedHost("objects.githubusercontent.com"))
        assertTrue(UpdateInstaller.isAllowedHost("release-assets.githubusercontent.com"))
        assertFalse(UpdateInstaller.isAllowedHost("evil.githubusercontent.com"))
        assertFalse(UpdateInstaller.isAllowedHost("attacker.example"))
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
