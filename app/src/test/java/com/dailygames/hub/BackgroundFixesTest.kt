package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.URL
import java.time.Instant
import java.util.TimeZone

/** Regression tests for the 1.0.1 background / data-layer fixes. */
class BackgroundFixesTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private class Recorder : NotificationDispatcher {
        val posted = mutableListOf<String>()
        var batches = 0
        override fun notifyGameRefresh(gameId: String, title: String, body: String) { posted += gameId }
        override fun onBatchComplete() { batches++ }
    }

    private val travelGames = """
        [
          {"id": "wordle", "name": "Wordle", "resetTime": "00:00", "resetZone": "local", "notifyEnabled": true},
          {"id": "feguess", "name": "FE Guess", "resetTime": "00:00", "resetZone": "utc", "notifyEnabled": true}
        ]
    """.trimIndent()

    @Test
    fun travellingWest_neitherReannouncesNorRewindsTheAnchor() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val honolulu = TimeZone.getTimeZone("Pacific/Honolulu")
        val storage = InMemoryGameStorage()
        GameRepository(storage, { ms("2026-11-07T14:00:00Z") }, tokyo).sync(travelGames)

        // Tokyo midnight: the local game rolls over to Nov 8.
        val inTokyo = Recorder()
        DailyRefreshProcessor(GameRepository(storage, localTimeZone = tokyo), inTokyo, tokyo)
            .processRefresh(ms("2026-11-07T15:00:01Z"))
        assertEquals(listOf("wordle"), inTokyo.posted)

        // Flown to Honolulu: at the UTC reset it is still Nov 7 there. Only the UTC game is new.
        val repoHnl = GameRepository(storage, localTimeZone = honolulu)
        val atUtcReset = Recorder()
        DailyRefreshProcessor(repoHnl, atUtcReset, honolulu).processRefresh(ms("2026-11-08T00:00:01Z"))
        assertEquals(listOf("feguess"), atUtcReset.posted)
        assertEquals("2026-11-08", repoHnl.lastNotifiedKey("wordle")) // not rewound to Nov 7

        // Honolulu midnight: Nov 8 again, already announced in Tokyo.
        val atLocalMidnight = Recorder()
        DailyRefreshProcessor(repoHnl, atLocalMidnight, honolulu).processRefresh(ms("2026-11-08T10:00:01Z"))
        assertTrue(atLocalMidnight.posted.isEmpty())

        // Next local midnight (Nov 9) is new again.
        val nextDay = Recorder()
        DailyRefreshProcessor(repoHnl, nextDay, honolulu).processRefresh(ms("2026-11-09T10:00:01Z"))
        assertEquals(listOf("wordle", "feguess"), nextDay.posted)
    }

    @Test
    fun clockSetBack_doesNotNotifyAgain() {
        val utc = TimeZone.getTimeZone("UTC")
        val storage = InMemoryGameStorage()
        GameRepository(storage, { ms("2026-03-14T12:00:00Z") }, utc).sync(travelGames)
        val repo = GameRepository(storage, localTimeZone = utc)
        val r = Recorder()
        DailyRefreshProcessor(repo, r, utc).processRefresh(ms("2026-03-15T00:00:02Z"))
        assertEquals(2, r.posted.size)
        DailyRefreshProcessor(repo, r, utc).processRefresh(ms("2026-03-14T23:00:00Z")) // clock back an hour
        DailyRefreshProcessor(repo, r, utc).processRefresh(ms("2026-03-15T00:00:02Z")) // and forward again
        assertEquals(2, r.posted.size)
        assertEquals("2026-03-15", repo.lastNotifiedKey("wordle"))
    }

    @Test
    fun batchCompleteRunsOncePerPassThatPosted() {
        val utc = TimeZone.getTimeZone("UTC")
        val storage = InMemoryGameStorage()
        GameRepository(storage, { ms("2026-03-14T12:00:00Z") }, utc).sync(travelGames)
        val repo = GameRepository(storage, localTimeZone = utc)
        val r = Recorder()
        DailyRefreshProcessor(repo, r, utc).processRefresh(ms("2026-03-14T13:00:00Z"))
        assertEquals(0, r.batches) // nothing posted -> no summary pass
        DailyRefreshProcessor(repo, r, utc).processRefresh(ms("2026-03-15T00:00:02Z"))
        assertEquals(2, r.posted.size)
        assertEquals(1, r.batches)
    }

    @Test
    fun corruptAnchor_isReanchoredSilently() {
        val utc = TimeZone.getTimeZone("UTC")
        val storage = InMemoryGameStorage()
        val repo = GameRepository(storage, { ms("2026-03-14T12:00:00Z") }, utc)
        repo.sync(travelGames)
        storage.putString(GameStore.NOTIFIED_PREFIX + "wordle", "garbage")
        val r = Recorder()
        DailyRefreshProcessor(repo, r, utc).processRefresh(ms("2026-03-14T13:00:00Z"))
        assertTrue(r.posted.isEmpty())
        assertEquals("2026-03-14", repo.lastNotifiedKey("wordle"))
    }

    @Test
    fun removedGame_forgetsItsAnchor_soReaddingStartsFresh() {
        val utc = TimeZone.getTimeZone("UTC")
        val storage = InMemoryGameStorage()
        var now = ms("2026-03-01T12:00:00Z")
        val repo = GameRepository(storage, { now }, utc)
        repo.sync(travelGames)
        assertEquals("2026-03-01", repo.lastNotifiedKey("wordle"))

        now = ms("2026-03-10T12:00:00Z")
        repo.sync("""[{"id": "feguess", "resetTime": "00:00", "resetZone": "utc"}]""")
        assertNull(repo.lastNotifiedKey("wordle"))

        repo.sync(travelGames) // re-added under the same id
        assertEquals("2026-03-10", repo.lastNotifiedKey("wordle"))
        val r = Recorder()
        DailyRefreshProcessor(repo, r, utc).processRefresh(ms("2026-03-10T13:00:00Z"))
        // feguess was never removed and really did roll over; the re-added wordle must not be announced.
        assertEquals(listOf("feguess"), r.posted)
    }

    @Test
    fun reenablingAlerts_reanchorsToTheCurrentCycle() {
        val utc = TimeZone.getTimeZone("UTC")
        val storage = InMemoryGameStorage()
        var now = ms("2026-03-01T12:00:00Z")
        val repo = GameRepository(storage, { now }, utc)
        val off = """[{"id": "g", "resetTime": "00:00", "resetZone": "utc", "notifyEnabled": false}]"""
        val on = """[{"id": "g", "resetTime": "00:00", "resetZone": "utc", "notifyEnabled": true}]"""
        repo.sync(off)
        now = ms("2026-03-09T12:00:00Z")
        repo.sync(off) // still off: anchor untouched
        assertEquals("2026-03-01", repo.lastNotifiedKey("g"))
        repo.sync(on)
        assertEquals("2026-03-09", repo.lastNotifiedKey("g"))
        val r = Recorder()
        DailyRefreshProcessor(repo, r, utc).processRefresh(ms("2026-03-09T13:00:00Z"))
        assertTrue(r.posted.isEmpty())
    }

    @Test
    fun bootAndUpdateProcessMissedResets_clockChangesOnlyReschedule() {
        assertEquals(ScheduleChangeReceiver.Mode.PROCESS_MISSED, ScheduleChangeReceiver.modeFor("android.intent.action.BOOT_COMPLETED"))
        assertEquals(ScheduleChangeReceiver.Mode.PROCESS_MISSED, ScheduleChangeReceiver.modeFor("android.intent.action.MY_PACKAGE_REPLACED"))
        assertEquals(ScheduleChangeReceiver.Mode.RESCHEDULE_ONLY, ScheduleChangeReceiver.modeFor("android.intent.action.TIMEZONE_CHANGED"))
        assertEquals(ScheduleChangeReceiver.Mode.RESCHEDULE_ONLY, ScheduleChangeReceiver.modeFor("android.intent.action.TIME_SET"))
        assertEquals(
            ScheduleChangeReceiver.Mode.RESCHEDULE_ONLY,
            ScheduleChangeReceiver.modeFor("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
        )
        assertNull(ScheduleChangeReceiver.modeFor("com.example.SOMETHING"))
        assertNull(ScheduleChangeReceiver.modeFor(null))
    }

    @Test
    fun groupSummary_countsJustPostedNotificationsOnce() {
        // The system hasn't registered id 1002 yet; 1001 is already showing; 1001 is re-posted.
        val entries = Notifier.summaryEntries(
            active = listOf(1001 to "Wordle has refreshed"),
            justPosted = linkedMapOf(1001 to "Wordle has refreshed", 1002 to "Nerdle has refreshed")
        )
        assertEquals(listOf("Wordle has refreshed", "Nerdle has refreshed"), entries)
        assertEquals(1, Notifier.summaryEntries(emptyList(), mapOf(1003 to "Globle has refreshed")).size)
    }

    @Test
    fun ipv6TransitionAndDocumentationRanges_areNotPublic() {
        listOf(
            "::127.0.0.1", "::192.168.1.1", "64:ff9b::c0a8:101", "64:ff9b::7f00:1", "2002:c0a8:101::1", "2002:7f00:1::1",
            "2001:0:4136:e378::1", "2001:db8::1", "100::1", "192.0.2.1", "198.51.100.7", "203.0.113.9"
        ).forEach { assertFalse(it, SafeHttp.isPublicAddress(InetAddress.getByName(it))) }
        // NAT64 / 6to4 wrapping a public IPv4 stay usable (IPv6-only mobile networks rely on NAT64).
        listOf("64:ff9b::808:808", "2002:808:808::1", "2606:4700:4700::1111", "8.8.8.8")
            .forEach { assertTrue(it, SafeHttp.isPublicAddress(InetAddress.getByName(it))) }
    }

    @Test
    fun pinnedHttpUrl_connectsToTheCheckedAddressKeepingPathAndPort() {
        val v4 = SafeHttp.pinnedHttpUrl(URL("http://games.example/daily?x=1"), InetAddress.getByName("93.184.216.34"))
        assertEquals("http://93.184.216.34/daily?x=1", v4.toString())
        val v6 = SafeHttp.pinnedHttpUrl(URL("http://games.example:8080/p"), InetAddress.getByName("2606:4700::1"))
        assertEquals("[2606:4700:0:0:0:0:0:1]", v6.host)
        assertEquals(8080, v6.port)
        assertEquals("/p", v6.file)
    }

    @Test
    fun resolvePublic_refusesLocalNames() {
        assertNull(SafeHttp.resolvePublic("localhost"))
        assertNull(SafeHttp.resolvePublic("127.0.0.1"))
        assertNull(SafeHttp.resolvePublic("router.local"))
        assertEquals(1, SafeHttp.resolvePublic("8.8.8.8")?.size)
    }
}
