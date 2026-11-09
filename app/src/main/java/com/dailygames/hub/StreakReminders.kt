package com.dailygames.hub

import androidx.core.content.edit
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import java.util.TimeZone

/** The "Streak reminders" notification channel (separate from reset alerts, so each can be muted). */
object ReminderChannel {
    const val ID = "streak_reminders"

    fun ensure(ctx: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm.createNotificationChannel(
            NotificationChannel(ID, "Streak Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminds you before a reset when you haven't logged a game you have a streak in."
            }
        )
    }
}

/**
 * Pure planning for streak reminders: which games to remind about now, and when to look next.
 * A game qualifies when it has a streak, the user hasn't logged it in the current cycle, and its
 * reset is within the reminder lead time. Each game is reminded at most once per cycle.
 */
class StreakReminderPlanner(private val localTimeZone: TimeZone = TimeZone.getDefault()) {
    class Candidate(val id: String, val name: String, val streak: Int, val msUntilReset: Long, val cycleKey: String)

    private fun candidates(games: JSONArray, nowMs: Long): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (i in 0 until games.length()) {
            val g = games.optJSONObject(i) ?: continue
            val id = g.optString("id")
            val streak = g.optInt("streak", 0)
            if (id.isEmpty() || streak <= 0 || !g.optBoolean("notifyEnabled", true)) continue
            val time = g.optString("resetTime", "00:00")
            val zone = g.optString("resetZone", "local")
            val key = GameStore.cycleKey(time, zone, nowMs, localTimeZone)
            if (g.optString("loggedCycle") == key) continue
            out += Candidate(id, g.optString("name").ifBlank { "Your daily game" }, streak,
                GameStore.msUntilNextReset(time, zone, nowMs, localTimeZone), key)
        }
        return out
    }

    /** Games to remind about at [nowMs] (window open, not logged, not yet reminded this cycle). */
    fun due(games: JSONArray, nowMs: Long, leadMs: Long, remindedCycle: (String) -> String?): List<Candidate> {
        if (leadMs <= 0) return emptyList()
        return candidates(games, nowMs).filter { it.msUntilReset <= leadMs + SLACK_MS && remindedCycle(it.id) != it.cycleKey }
            .sortedBy { it.msUntilReset }
    }

    /**
     * When to check next: now if a window is already open for a game that still needs a reminder,
     * otherwise the earliest upcoming "reset minus lead" among games with a streak; null if none.
     */
    fun nextCheck(games: JSONArray, nowMs: Long, leadMs: Long, remindedCycle: (String) -> String?): Long? {
        if (leadMs <= 0) return null
        var best: Long? = null
        for (i in 0 until games.length()) {
            val g = games.optJSONObject(i) ?: continue
            val id = g.optString("id")
            if (id.isEmpty() || g.optInt("streak", 0) <= 0 || !g.optBoolean("notifyEnabled", true)) continue
            val time = g.optString("resetTime", "00:00")
            val zone = g.optString("resetZone", "local")
            val untilReset = GameStore.msUntilNextReset(time, zone, nowMs, localTimeZone)
            val key = GameStore.cycleKey(time, zone, nowMs, localTimeZone)
            val open = g.optString("loggedCycle") != key && remindedCycle(id) != key
            // Inside the window: remind now if still needed, else look again when tomorrow's opens
            // (approximately a day on; each check re-plans from the real next reset, so DST is absorbed).
            val at = when {
                untilReset > leadMs -> nowMs + untilReset - leadMs
                open -> nowMs
                else -> nowMs + untilReset + DAY_MS - leadMs
            }
            if (best == null || at < best) best = at
        }
        return best
    }

    /** Notification text: "Wordle resets in 1 hr" / "3 streaks reset within 2 hr". */
    fun message(due: List<Candidate>, leadMs: Long): Pair<String, String> {
        val lead = formatLead(leadMs)
        return if (due.size == 1) {
            val c = due[0]
            "Keep your ${c.name} streak" to "${c.streak}-day streak · ${c.name} resets within $lead and you haven't logged it yet."
        } else {
            "Keep your ${due.size} streaks going" to due.joinToString(", ") { "${it.name} (${it.streak}d)" } + " reset within $lead."
        }
    }

    companion object {
        const val SLACK_MS = 90_000L
        const val DAY_MS = 24 * 60 * 60_000L
        val ALLOWED_LEAD_MINUTES = setOf(0, 30, 60, 120, 180, 360)

        fun formatLead(ms: Long): String {
            val min = (ms / 60_000L).toInt()
            return when {
                min < 60 -> "$min min"
                min % 60 == 0 -> "${min / 60} hr"
                else -> "${min / 60} hr ${min % 60} min"
            }
        }
    }
}

/** Settings, alarm and notification for streak reminders. */
object StreakReminders {
    private const val PREFS = "dailes_reminders"
    private const val KEY_LEAD = "lead_minutes"
    private const val REMINDED_PREFIX = "reminded_"
    private const val REQUEST_CODE = 4102
    private const val NOTIFICATION_ID = 0x0DA1E6
    const val ACTION = "com.dailygames.hub.action.STREAK_REMINDER"

    fun leadMinutes(ctx: Context): Int = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LEAD, 0)

    fun setLeadMinutes(ctx: Context, minutes: Int) {
        val m = if (minutes in StreakReminderPlanner.ALLOWED_LEAD_MINUTES) minutes else 0
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_LEAD, m) }
        schedule(ctx)
    }

    /** Posts any due reminder, clears one the user has since dealt with, and arms the next check. */
    fun run(ctx: Context, nowMs: Long = System.currentTimeMillis()) = synchronized(ScheduleLock) {
        val leadMs = leadMinutes(ctx) * 60_000L
        val games = GameStore.load(ctx)
        val planner = StreakReminderPlanner()
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val due = planner.due(games, nowMs, leadMs) { prefs.getString(REMINDED_PREFIX + it, null) }
        if (due.isNotEmpty()) {
            post(ctx, planner.message(due, leadMs))
            // Marked even if notifications are off, so the alarm doesn't keep firing for this cycle.
            prefs.edit { due.forEach { putString(REMINDED_PREFIX + it.id, it.cycleKey) } }
        }
        scheduleLocked(ctx, games, nowMs, leadMs)
    }

    /** Re-plans after the games list or the setting changes. A shown reminder goes once nothing is left to log. */
    fun schedule(ctx: Context, nowMs: Long = System.currentTimeMillis()) = synchronized(ScheduleLock) {
        val leadMs = leadMinutes(ctx) * 60_000L
        val games = GameStore.load(ctx)
        val stillOpen = StreakReminderPlanner().due(games, nowMs, leadMs) { null }.isNotEmpty()
        if (!stillOpen) runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID) }
        scheduleLocked(ctx, games, nowMs, leadMs)
    }

    private fun scheduleLocked(ctx: Context, games: JSONArray, nowMs: Long, leadMs: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = StreakReminderPlanner().nextCheck(games, nowMs, leadMs) { prefs.getString(REMINDED_PREFIX + it, null) }
        if (at == null) { runCatching { am.cancel(pendingIntent(ctx)) }; return }
        // A reminder minutes late is fine, so an inexact alarm is enough when exact ones aren't allowed.
        runCatching {
            if (ResetAlarms.canScheduleExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(ctx))
        }
    }

    private fun post(ctx: Context, text: Pair<String, String>): Boolean {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        Notifier.ensureChannel(ctx)
        val launch = Intent(ctx, HubActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pending = PendingIntent.getActivity(ctx, NOTIFICATION_ID, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, ReminderChannel.ID)
            .setSmallIcon(R.drawable.ic_stat_dailes)
            .setContentTitle(text.first)
            .setContentText(text.second)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.second))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        return runCatching { nm.notify(NOTIFICATION_ID, n) }.isSuccess
    }

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQUEST_CODE, Intent(ctx, StreakReminderReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class StreakReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try { StreakReminders.run(app) } finally { pending.finish() }
        }.start()
    }
}
