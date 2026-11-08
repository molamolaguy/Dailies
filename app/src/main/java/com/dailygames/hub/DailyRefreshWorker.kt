package com.dailygames.hub

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Constructor-injected processor that evaluates all tracked games against their reset schedules
 * and dispatches per-game notifications when a new daily cycle starts.
 * Streak and missed-day history are owned exclusively by the JS hub state (K9).
 */
class DailyRefreshProcessor(
    private val repository: GameRepository,
    private val dispatcher: NotificationDispatcher,
    private val localTimeZone: TimeZone = TimeZone.getDefault()
) {
    /**
     * Checks all stored games at [nowMs] and returns the number of notifications dispatched.
     */
    fun processRefresh(nowMs: Long = System.currentTimeMillis()): Int = synchronized(ScheduleLock) {
        processLocked(nowMs)
    }

    private fun processLocked(nowMs: Long): Int {
        val games = repository.load()
        var notifiedCount = 0

        for (i in 0 until games.length()) {
            val g = games.optJSONObject(i) ?: continue
            val id = g.optString("id")
            if (id.isEmpty()) continue

            val resetTime = g.optString("resetTime", "00:00")
            val resetZone = g.optString("resetZone", "local")
            val currentKey = GameStore.cycleKey(
                resetTime,
                resetZone,
                nowMs,
                localTimeZone
            )
            val lastKey = repository.lastNotifiedKey(id)

            if (lastKey == null) {
                repository.setNotifiedKey(id, currentKey)
                continue
            }
            if (lastKey != currentKey) {
                if (g.optBoolean("notifyEnabled", true)) {
                    val (title, body) = Notifier.formatRefreshNotification(
                        g.optString("icon", ""),
                        g.optString("name", "Your daily game")
                    )
                    dispatcher.notifyGameRefresh(id, title, body)
                    notifiedCount++
                }
                repository.setNotifiedKey(id, currentKey)
            }
        }
        return notifiedCount
    }

}

/**
 * Backup trigger for the next upcoming reset across all enabled games (P12). The primary trigger is
 * [ResetAlarms]; this OneTimeWorkRequest fires a few minutes later in case the alarm was dropped.
 */
class DailyRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        runRefresh(applicationContext)
        return Result.success()
    }

    companion object {
        const val NEXT_RESET_WORK_NAME = "daily_game_next_reset"
        const val LEGACY_PERIODIC_WORK_NAME = "daily_game_refresh_check"
        private const val BACKUP_DELAY_MS = 5 * 60_000L

        /** Posts notifications for every game whose cycle has rolled over, then arms the next reset. */
        fun runRefresh(ctx: Context) {
            val processor = DailyRefreshProcessor(
                repository = GameStore.repository(ctx),
                dispatcher = { id, title, body -> Notifier.postGameNotification(ctx, id, title, body) }
            )
            processor.processRefresh()
            scheduleNextReset(ctx)
        }

        fun scheduleNextReset(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)

            val games = GameStore.load(ctx)
            val minDelayMs = GameStore.nextResetDelayMs(games, nowMs)
            if (minDelayMs == null) {
                wm.cancelUniqueWork(NEXT_RESET_WORK_NAME)
                ResetAlarms.cancel(ctx)
                return
            }

            // Primary, close-to-on-time trigger.
            ResetAlarms.schedule(ctx, nowMs + minDelayMs + 1_500L, nowMs)

            // Backup in case the alarm is dropped; a few minutes later so it normally finds nothing to do.

            val oneTimeReq = OneTimeWorkRequestBuilder<DailyRefreshWorker>()
                .setInitialDelay(minDelayMs + BACKUP_DELAY_MS, TimeUnit.MILLISECONDS)
                .addTag(NEXT_RESET_WORK_NAME)
                .build()
            wm.enqueueUniqueWork(
                NEXT_RESET_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                oneTimeReq
            )
        }
    }
}
