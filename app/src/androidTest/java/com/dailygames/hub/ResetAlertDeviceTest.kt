package com.dailygames.hub

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole reset-alert pass on a real Android, as the alarm runs it (DailyRefreshWorker.runRefresh):
 * new puzzles found, alerts posted, next reset and streak reminders armed. The alerts must still be in
 * the shade afterwards. Until 1.8.2 the combined alert for several games was removed straight away by
 * the streak-reminder clean-up, which shared its notification id.
 */
@RunWith(AndroidJUnit4::class)
class ResetAlertDeviceTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val nm get() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        nm.cancelAll()
        ctx.getSharedPreferences(GameStore.PREFS, Context.MODE_PRIVATE).edit { clear() }
    }

    @After
    fun tearDown() {
        nm.cancelAll()
        ctx.getSharedPreferences(GameStore.PREFS, Context.MODE_PRIVATE).edit { clear() }
    }

    /** Syncs [ids] as games resetting at midnight UTC and marks yesterday's puzzle as the last announced. */
    private fun gamesWithANewPuzzle(vararg ids: String) {
        val json = ids.joinToString(",", "[", "]") {
            """{"id":"$it","name":"Test $it","resetTime":"00:00","resetZone":"utc","notifyEnabled":true,"streak":3}"""
        }
        GameStore.sync(ctx, json)
        val yesterday = GameStore.cycleKey("00:00", "utc", System.currentTimeMillis() - 86_400_000L)
        ids.forEach { GameStore.setNotifiedKey(ctx, it, yesterday) }
    }

    private fun activeIds(): Set<Int> {
        // Posting is asynchronous in the system server: give it a moment.
        Thread.sleep(1500)
        return nm.activeNotifications.map { it.id }.toSet()
    }

    @Test
    fun severalGamesGetOneCombinedAlertThatStays() {
        gamesWithANewPuzzle("alert-a", "alert-b", "alert-c")
        DailyRefreshWorker.runRefresh(ctx)
        val ids = activeIds()
        assertTrue("combined reset alert missing after the refresh pass; active: $ids", Notifier.COMBINED_ID in ids)
    }

    @Test
    fun oneGameGetsItsOwnAlert() {
        gamesWithANewPuzzle("alert-solo")
        DailyRefreshWorker.runRefresh(ctx)
        val ids = activeIds()
        assertTrue("single reset alert missing; active: $ids", Notifier.notificationIdFor(ctx, "alert-solo") in ids)
    }

    @Test
    fun aLaterScheduleChangeDoesNotClearTheAlert() {
        gamesWithANewPuzzle("alert-x", "alert-y")
        DailyRefreshWorker.runRefresh(ctx)
        // The hub syncing its games again (e.g. opened after the alert) re-plans reminders and alarms.
        DailyRefreshWorker.refreshAndSchedule(ctx)
        val ids = activeIds()
        assertTrue("combined alert was cleared by re-scheduling; active: $ids", Notifier.COMBINED_ID in ids)
    }
}
