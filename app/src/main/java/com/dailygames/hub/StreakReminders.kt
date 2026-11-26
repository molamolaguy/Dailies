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
import org.json.JSONObject
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

    fun isBlocked(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return nm.getNotificationChannel(ID)?.importance == NotificationManager.IMPORTANCE_NONE
    }
}

/**
 * Pure planning for streak reminders: which games to remind about now, and when to look next.
 * A game qualifies when it has a streak, the user hasn't logged it in the current cycle, and its
 * reset is within the reminder lead time. Each game is reminded at most once per cycle.
 *
 * The lead time is the app-wide setting unless the game sets its own `reminderMinutes`
 * (0 = never remind for this game; absent or -1 = use the app-wide setting).
 */
class StreakReminderPlanner(private val localTimeZone: TimeZone = TimeZone.getDefault()) {
    class Candidate(
        val id: String,
        val name: String,
        val streak: Int,
        val msUntilReset: Long,
        val cycleKey: String,
        val leadMs: Long = 0L
    )

    /** This game's lead time: its own setting when it has one, else [defaultLeadMs] when reset alerts are enabled. */
    fun leadFor(g: JSONObject, defaultLeadMs: Long): Long {
        val own = if (g.has("reminderMinutes")) g.optInt("reminderMinutes", -1) else -1
        return when {
            own < 0 -> if (g.optBoolean("notifyEnabled", true)) defaultLeadMs else 0L
            own in ALLOWED_LEAD_MINUTES -> own * 60_000L
            else -> if (g.optBoolean("notifyEnabled", true)) defaultLeadMs else 0L
        }
    }

    private fun previousCycleKey(key: String): String {
        val parts = key.split('-')
        if (parts.size != 3) return ""
        val y = parts[0].toIntOrNull() ?: return ""
        val m = parts[1].toIntOrNull() ?: return ""
        val d = parts[2].toIntOrNull() ?: return ""
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y, m - 1, d)
            add(java.util.Calendar.DAY_OF_MONTH, -1)
        }
        return String.format(
            java.util.Locale.US, "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun isBrokenStreak(g: JSONObject, cycleKey: String): Boolean {
        val last = g.optString("lastLoggedCycle").ifEmpty { g.optString("loggedCycle") }
        if (last.isEmpty()) return false
        val yesterday = previousCycleKey(cycleKey)
        return yesterday.isNotEmpty() && last < yesterday
    }

    private fun candidates(games: JSONArray, nowMs: Long, defaultLeadMs: Long): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (i in 0 until games.length()) {
            val g = games.optJSONObject(i) ?: continue
            val id = g.optString("id")
            val streak = g.optInt("streak", 0)
            if (id.isEmpty() || streak <= 0) continue
            val lead = leadFor(g, defaultLeadMs)
            if (lead <= 0) continue
            val time = g.optString("resetTime", "00:00")
            val zone = g.optString("resetZone", "local")
            val key = GameStore.cycleKey(time, zone, nowMs, localTimeZone)
            if (g.optString("loggedCycle") == key || isBrokenStreak(g, key)) continue
            out += Candidate(
                id,
                g.optString("name").ifBlank { "Your daily game" },
                streak,
                GameStore.msUntilNextReset(time, zone, nowMs, localTimeZone),
                key,
                lead
            )
        }
        return out
    }

    /** Games to remind about at [nowMs] (window open, not logged, not yet reminded this cycle). */
    fun due(games: JSONArray, nowMs: Long, leadMs: Long, remindedCycle: (String) -> String?): List<Candidate> =
        candidates(games, nowMs, leadMs).filter { it.msUntilReset <= it.leadMs + SLACK_MS && remindedCycle(it.id) != it.cycleKey }
            .sortedBy { it.msUntilReset }

    /**
     * All games whose reminder window is currently open and haven't been logged yet in this cycle,
     * regardless of whether they were already reminded. Used to reconcile an active shade notification
     * when games are logged or new reminder windows open.
     */
    fun openDue(games: JSONArray, nowMs: Long, leadMs: Long): List<Candidate> =
        candidates(games, nowMs, leadMs).filter { it.msUntilReset <= it.leadMs + SLACK_MS }
            .sortedBy { it.msUntilReset }

    /**
     * When to check next: now if a window is already open for a game that still needs a reminder,
     * otherwise the earliest upcoming "reset minus lead" among games with a streak; null if none.
     */
    fun nextCheck(games: JSONArray, nowMs: Long, defaultLeadMs: Long, remindedCycle: (String) -> String?): Long? {
        var best: Long? = null
        for (i in 0 until games.length()) {
            val g = games.optJSONObject(i) ?: continue
            val id = g.optString("id")
            if (id.isEmpty() || g.optInt("streak", 0) <= 0) continue
            val leadMs = leadFor(g, defaultLeadMs)
            if (leadMs <= 0) continue
            val time = g.optString("resetTime", "00:00")
            val zone = g.optString("resetZone", "local")
            val key = GameStore.cycleKey(time, zone, nowMs, localTimeZone)
            if (isBrokenStreak(g, key)) continue
            val untilReset = GameStore.msUntilNextReset(time, zone, nowMs, localTimeZone)
            val open = g.optString("loggedCycle") != key && remindedCycle(id) != key
            val at = when {
                open && untilReset > leadMs -> nowMs + untilReset - leadMs
                open -> nowMs
                else -> {
                    val afterReset = nowMs + untilReset + 60_000L
                    val nextReset = afterReset + GameStore.msUntilNextReset(time, zone, afterReset, localTimeZone)
                    nextReset - leadMs
                }
            }
            if (best == null || at < best) best = at
        }
        return best
    }

    /** Notification text: "Keep your Wordle streak" / "Keep your 3 streaks going". */
    fun message(due: List<Candidate>, defaultLeadMs: Long): Pair<String, String> {
        if (due.isEmpty()) return "" to ""
        return if (due.size == 1) {
            val c = due[0]
            val windowMs = when {
                c.msUntilReset > 0L && c.leadMs > 0L -> minOf(c.msUntilReset, c.leadMs)
                c.msUntilReset > 0L -> c.msUntilReset
                c.leadMs > 0L -> c.leadMs
                else -> defaultLeadMs
            }
            val lead = formatLead(windowMs)
            "Keep your ${c.name} streak" to "${c.streak}-day streak · ${c.name} resets within $lead and you haven't logged it yet."
        } else {
            val distinctWindows = due.map { c ->
                when {
                    c.msUntilReset > 0L && c.leadMs > 0L -> minOf(c.msUntilReset, c.leadMs)
                    c.msUntilReset > 0L -> c.msUntilReset
                    c.leadMs > 0L -> c.leadMs
                    else -> defaultLeadMs
                }
            }.map { formatLead(it) }.toSet()
            if (distinctWindows.size == 1) {
                val lead = distinctWindows.first()
                "Keep your ${due.size} streaks going" to due.joinToString(", ") { "${it.name} (${it.streak}d)" } + " reset within $lead."
            } else {
                val perGame = due.joinToString(", ") { c ->
                    val w = when {
                        c.msUntilReset > 0L && c.leadMs > 0L -> minOf(c.msUntilReset, c.leadMs)
                        c.msUntilReset > 0L -> c.msUntilReset
                        c.leadMs > 0L -> c.leadMs
                        else -> defaultLeadMs
                    }
                    "${c.name} (${c.streak}d, ${formatLead(w)})"
                }
                "Keep your ${due.size} streaks going" to "$perGame reset soon."
            }
        }
    }

    companion object {
        const val SLACK_MS = 90_000L
        const val DAY_MS = 24 * 60 * 60_000L
        val ALLOWED_LEAD_MINUTES = setOf(0, 30, 60, 120, 180, 360)

        fun formatLead(ms: Long): String {
            val min = ((ms + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
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
    private const val EXTRA_REMINDER_ITEMS = "dailes_reminder_items"
    private const val REQUEST_CODE = 4102
    /** Its own id: 0x0DA1E6 is the combined reset alert ([Notifier.COMBINED_ID]), which [schedule] used to clear. */
    const val NOTIFICATION_ID = 0x0DA1E8
    const val ACTION = "com.dailygames.hub.action.STREAK_REMINDER"

    fun leadMinutes(ctx: Context): Int = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LEAD, 0)

    fun setLeadMinutes(ctx: Context, minutes: Int) {
        val m = if (minutes in StreakReminderPlanner.ALLOWED_LEAD_MINUTES) minutes else 0
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { putInt(KEY_LEAD, m) }
        schedule(ctx)
    }

    /** Removes `reminded_<id>` entries for games no longer in [activeIds]. */
    fun prune(ctx: Context, activeIds: Set<String>) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stale = prefs.all.keys.filter { it.startsWith(REMINDED_PREFIX) && it.removePrefix(REMINDED_PREFIX) !in activeIds }
        if (stale.isNotEmpty()) {
            prefs.edit(commit = true) { stale.forEach { remove(it) } }
        }
    }

    /** Serializes [candidates] into JSON for [EXTRA_REMINDER_ITEMS] in the shade notification. */
    internal fun encodeItems(candidates: List<StreakReminderPlanner.Candidate>): String =
        JSONArray().also { arr ->
            candidates.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("streak", c.streak)
                        .put("msUntilReset", c.msUntilReset)
                        .put("leadMs", c.leadMs)
                        .put("cycleKey", c.cycleKey)
                )
            }
        }.toString()

    internal fun decodeItems(raw: String?): List<StreakReminderPlanner.Candidate> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<StreakReminderPlanner.Candidate>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            out += StreakReminderPlanner.Candidate(
                id = id,
                name = o.optString("name").ifBlank { "Your daily game" },
                streak = o.optInt("streak", 1),
                msUntilReset = o.optLong("msUntilReset", 0L),
                cycleKey = o.optString("cycleKey", ""),
                leadMs = o.optLong("leadMs", 0L)
            )
        }
        return out
    }

    /**
     * Merges unread shade reminder items (filtered to games that are still open and unlogged in
     * [stillOpen]) with freshly due candidates, deduplicated by game id and ordered by soonest reset.
     */
    internal fun mergeShadeItems(
        inShade: List<StreakReminderPlanner.Candidate>,
        fresh: List<StreakReminderPlanner.Candidate>,
        stillOpen: List<StreakReminderPlanner.Candidate>
    ): List<StreakReminderPlanner.Candidate> {
        val openById = stillOpen.associateBy { it.id }
        val byId = LinkedHashMap<String, StreakReminderPlanner.Candidate>()
        inShade.forEach { old ->
            val current = openById[old.id]
            if (current != null && (old.cycleKey.isEmpty() || old.cycleKey == current.cycleKey)) {
                byId[current.id] = current
            }
        }
        fresh.forEach { c -> byId[c.id] = c }
        return byId.values.sortedBy { it.msUntilReset }
    }

    private fun activeReminderItems(ctx: Context): List<StreakReminderPlanner.Candidate>? {
        val systemNm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
        val sbn = runCatching { systemNm.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID } }.getOrNull()
            ?: return null
        return decodeItems(sbn.notification.extras?.getString(EXTRA_REMINDER_ITEMS))
    }

    /** Posts any due reminder (merging with unread shade items), and arms the next check. */
    fun run(ctx: Context, nowMs: Long = System.currentTimeMillis()) = synchronized(ScheduleLock) {
        val leadMs = leadMinutes(ctx) * 60_000L
        val games = GameStore.load(ctx)
        val planner = StreakReminderPlanner()
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val due = planner.due(games, nowMs, leadMs) { prefs.getString(REMINDED_PREFIX + it, null) }
        val stillOpen = planner.openDue(games, nowMs, leadMs)
        if (due.isNotEmpty()) {
            val inShade = activeReminderItems(ctx).orEmpty()
            val merged = mergeShadeItems(inShade, due, stillOpen).ifEmpty { due }
            if (post(ctx, planner.message(merged, leadMs), merged)) {
                prefs.edit(commit = true) { due.forEach { putString(REMINDED_PREFIX + it.id, it.cycleKey) } }
            }
        } else {
            reconcileActiveReminder(ctx, planner, stillOpen, leadMs)
        }
        scheduleLocked(ctx, games, nowMs, leadMs)
    }

    /** Re-plans after the games list or the setting changes. Updates or clears an active shade reminder. */
    fun schedule(ctx: Context, nowMs: Long = System.currentTimeMillis()) = synchronized(ScheduleLock) {
        val leadMs = leadMinutes(ctx) * 60_000L
        val games = GameStore.load(ctx)
        val planner = StreakReminderPlanner()
        val stillOpen = planner.openDue(games, nowMs, leadMs)
        reconcileActiveReminder(ctx, planner, stillOpen, leadMs)
        scheduleLocked(ctx, games, nowMs, leadMs)
    }

    private fun reconcileActiveReminder(
        ctx: Context,
        planner: StreakReminderPlanner,
        stillOpen: List<StreakReminderPlanner.Candidate>,
        leadMs: Long
    ) {
        if (stillOpen.isEmpty()) {
            runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID) }
            return
        }
        val inShade = activeReminderItems(ctx) ?: return
        if (inShade.isEmpty()) return
        val remaining = mergeShadeItems(inShade, emptyList(), stillOpen)
        if (remaining.isEmpty()) {
            runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID) }
        } else if (remaining.map { it.id }.toSet() != inShade.map { it.id }.toSet()) {
            post(ctx, planner.message(remaining, leadMs), remaining, silent = true)
        }
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

    private fun post(
        ctx: Context,
        text: Pair<String, String>,
        items: List<StreakReminderPlanner.Candidate>,
        silent: Boolean = false
    ): Boolean {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (ReminderChannel.isBlocked(ctx)) return false
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
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setContentIntent(pending)
            .addExtras(android.os.Bundle().apply { putString(EXTRA_REMINDER_ITEMS, encodeItems(items)) })
            .let { Notifier.withAppIcon(ctx, it) }
            .build()
        return runCatching { nm.notify(NOTIFICATION_ID, n); true }.getOrDefault(false)
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

