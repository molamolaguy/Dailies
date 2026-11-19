package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's fixed notification ids must all differ. Until 1.8.2 the streak reminder used the same id
 * as the combined "several games are ready" alert, and StreakReminders.schedule() (run after every
 * reset pass) cancelled it a moment after it was posted, so bundled reset alerts never showed.
 */
class NotificationIdsTest {

    private val fixedIds = mapOf(
        "Notifier.SUMMARY_ID" to Notifier.SUMMARY_ID,
        "Notifier.COMBINED_ID" to Notifier.COMBINED_ID,
        "StreakReminders.NOTIFICATION_ID" to StreakReminders.NOTIFICATION_ID,
        "UpdateInstaller.NOTIFICATION_ID" to UpdateInstaller.NOTIFICATION_ID
    )

    @Test
    fun fixedNotificationIdsAreUnique() {
        assertEquals("duplicate notification ids: $fixedIds", fixedIds.size, fixedIds.values.toSet().size)
    }

    @Test
    fun streakReminderNeverClearsTheCombinedResetAlert() {
        assertTrue(StreakReminders.NOTIFICATION_ID != Notifier.COMBINED_ID)
        assertTrue(StreakReminders.NOTIFICATION_ID != Notifier.SUMMARY_ID)
    }

    @Test
    fun perGameIdsStayClearOfTheFixedIds() {
        // Per-game ids are handed out from 1001 upwards (Notifier.notificationIdFor); the fixed ids sit
        // far above any number of games.
        fixedIds.values.forEach { assertTrue("fixed id $it is too low", it > 100_000) }
    }
}
