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

            if (lastKey == null || !CYCLE_KEY_RE.matches(lastKey)) {
                repository.setNotifiedKey(id, currentKey)
                continue
            }
            // Only a cycle that is *newer* than the last notified one is a new puzzle. The key moves
            // backwards when the user travels west or sets the clock back; that must neither notify nor
            // rewind the anchor, or the same puzzle would be announced again when the clock catches up.
            // (Schedule edits re-anchor in GameRepository.sync instead.) ISO dates compare as strings.
            if (currentKey > lastKey) {
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
        if (notifiedCount > 0) dispatcher.onBatchComplete()
        return notifiedCount
    }

    private companion object {
        val CYCLE_KEY_RE = Regex("""\d{4}-\d{2}-\d{2}""")
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
            val posted = LinkedHashMap<Int, String>()
            val dispatcher = object : NotificationDispatcher {
                override fun notifyGameRefresh(gameId: String, title: String, body: String) {
                    Notifier.postGameNotification(ctx, gameId, title, body, updateSummary = false)
                        ?.let { posted[it] = title }
                }

                // One summary for the whole pass, counting this pass's posts directly.
                override fun onBatchComplete() {
                    if (posted.isNotEmpty()) Notifier.postGroupSummary(ctx, posted)
                }
            }
            val processor = DailyRefreshProcessor(
                repository = GameStore.repository(ctx),
                dispatcher = dispatcher
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
