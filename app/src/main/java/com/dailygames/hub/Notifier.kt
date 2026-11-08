package com.dailygames.hub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Abstraction for dispatching game refresh notifications (enables constructor DI in tests). */
fun interface NotificationDispatcher {
    fun notifyGameRefresh(gameId: String, title: String, body: String)
}

/** Shared notification helper used by both the UI bridge and the background worker. */
object Notifier {
    const val CHANNEL_ID = "daily_game_refresh"
    const val EXTRA_GAME_ID = "open_game_id"
    const val DEFAULT_REFRESH_BODY = "A new daily puzzle is ready. Tap to play and keep your streak going."
    const val GROUP_KEY = "com.dailygames.hub.RESETS"
    /** App-private secret on notification taps, so other apps can't make the hub open games. */
    const val EXTRA_TOKEN = "dailes_token"
    private const val PREFS = "dailes_notifier"

    /** Stable per-game notification id (sequential, never a hashCode collision). */
    @Synchronized
    fun notificationIdFor(ctx: Context, gameId: String): Int {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "id_$gameId"
        val existing = prefs.getInt(key, 0)
        if (existing != 0) return existing
        val next = prefs.getInt("next_id", 1000) + 1
        prefs.edit().putInt(key, next).putInt("next_id", next).apply()
        return next
    }

    @Synchronized
    fun intentToken(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("token", null)?.let { return it }
        val t = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("token", t).apply()
        return t
    }
    private const val SUMMARY_ID = 0x0DA1E5

    /**
     * Pure helper that formats the notification title and body for a refreshed game. No emoji: the
     * notification already carries the app's monochrome status-bar icon.
     */
    @Suppress("UNUSED_PARAMETER")
    fun formatRefreshNotification(rawIcon: String?, rawName: String?): Pair<String, String> {
        val cleanName = rawName?.trim().takeUnless { it.isNullOrEmpty() } ?: "Your daily game"
        return "$cleanName has refreshed" to DEFAULT_REFRESH_BODY
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reset Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies you when a daily game you track resets and a new puzzle is out."
                setSound(null, null)
            }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /** Posts one notification per game; tapping it opens that game inside Dailies. */
    fun postGameNotification(ctx: Context, gameId: String, title: String, body: String) {
        ensureChannel(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val notificationId = notificationIdFor(ctx, gameId)
        val launch = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_GAME_ID, gameId)
            putExtra(EXTRA_TOKEN, intentToken(ctx))
        }
        val pending = PendingIntent.getActivity(
            ctx,
            notificationId,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dailes)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setGroup(GROUP_KEY)
            .build()

        try {
            nm.notify(notificationId, notification)
            postGroupSummaryIfNeeded(ctx, nm)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call; nothing to do.
        }
    }

    /**
     * When several games reset at once, bundle them under one summary instead of a wall of
     * separate alerts.
     */
    private fun postGroupSummaryIfNeeded(ctx: Context, nm: NotificationManagerCompat) {
        val systemNm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val active = runCatching { systemNm.activeNotifications.toList() }.getOrDefault(emptyList())
            .filter { it.notification.group == GROUP_KEY && it.id != SUMMARY_ID }
        if (active.size < 2) return

        val style = NotificationCompat.InboxStyle()
        active.take(6).forEach { sbn ->
            val title = sbn.notification.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)
            if (!title.isNullOrBlank()) style.addLine(title)
        }
        val summaryText = "${active.size} daily games are ready"
        style.setSummaryText(summaryText)

        val launch = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            ctx, SUMMARY_ID, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summary = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dailes)
            .setContentTitle(summaryText)
            .setContentText("Tap to open your hub")
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .build()
        try {
            nm.notify(SUMMARY_ID, summary)
        } catch (e: SecurityException) {
            // Permission revoked between the check in postGameNotification and now.
        }
    }
}
