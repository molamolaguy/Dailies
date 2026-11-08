package com.dailygames.hub

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Wakes the app at the next reset so the "new puzzle" notification is on time.
 *
 * - Preferred: an exact "allow while idle" alarm (USE_EXACT_ALARM on Android 13+, SCHEDULE_EXACT_ALARM
 *   on 12/12L; both are granted at install for sideloaded apps).
 * - Fallback when exact alarms aren't allowed: inexact alarms may fire up to 75% of their delay late,
 *   so the app re-arms at half the remaining time until the last 10 minutes. Each stage then always
 *   fires before the reset, and the final one is at most a few minutes late.
 * WorkManager stays scheduled as a further backup.
 */
object ResetAlarms {
    private const val REQUEST_CODE = 4101
    const val ACTION_RESET_ALARM = "com.dailygames.hub.action.RESET_ALARM"
    private const val FINAL_STAGE_MS = 10 * 60_000L

    fun canScheduleExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    /** When to fire next for a reset at [resetAtMs]: the reset itself, or a staging point before it. */
    fun nextTrigger(resetAtMs: Long, nowMs: Long, exact: Boolean): Long {
        val remaining = resetAtMs - nowMs
        if (exact || remaining <= FINAL_STAGE_MS) return resetAtMs
        return nowMs + remaining / 2
    }

    fun schedule(ctx: Context, resetAtMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val exact = canScheduleExact(ctx)
        val at = nextTrigger(resetAtMs, nowMs, exact)
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(ctx))
        } catch (e: Exception) {
            // SecurityException if exact alarms were revoked between the check and the call.
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger(resetAtMs, nowMs, false), pendingIntent(ctx)) }
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(ctx)) }
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, ResetAlarmReceiver::class.java).setAction(ACTION_RESET_ALARM)
        return PendingIntent.getBroadcast(
            ctx, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** Fires at (or just after) the next reset: posts the due notifications and arms the following one. */
class ResetAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                DailyRefreshWorker.runRefresh(app)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/**
 * Re-arms the reset schedule after events that invalidate it: reboot (alarms are cleared), app update,
 * time-zone change (travel / DST rules) and manual clock changes.
 */
class ScheduleChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED -> {
                val app = context.applicationContext
                val pending = goAsync()
                Thread {
                    try {
                        // Don't notify for anything here, just re-anchor timing to the new clock.
                        DailyRefreshWorker.scheduleNextReset(app)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }
}
