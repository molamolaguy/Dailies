package com.dailygames.hub

import androidx.core.content.edit
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

    /** Called once after a refresh pass has dispatched all of its notifications (e.g. to post a group summary). */
    fun onBatchComplete() {}
}

/** Shared notification helper used by both the UI bridge and the background worker. */
object Notifier {
    /** 1.0.0/1.0.1 channel. It was created silent, and Android never lets an app change a channel's sound. */
    const val LEGACY_CHANNEL_ID = "daily_game_refresh"
    /** Since 1.1.0: same alerts, created with the phone's default notification sound. */
    const val SOUND_CHANNEL_ID = "reset_alerts"
    private const val KEY_CHANNEL = "channel_id"
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
        prefs.edit { putInt(key, next); putInt("next_id", next) }
        return next
    }

    @Synchronized
    fun intentToken(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("token", null)?.let { return it }
        val t = java.util.UUID.randomUUID().toString()
        prefs.edit { putString("token", t) }
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

    /** What to do with the old silent channel on first run of 1.1.0. */
    enum class ChannelMigration { KEEP_LEGACY, REPLACE_LEGACY, CREATE_NEW }

    /**
     * Keep the old channel if the user has already made a choice on it (blocked it, or turned sound
     * on); otherwise replace it with the new channel that has sound, so upgraders get the default too.
     */
    fun channelMigration(legacyExists: Boolean, legacyBlocked: Boolean, legacyHasSound: Boolean): ChannelMigration = when {
        !legacyExists -> ChannelMigration.CREATE_NEW
        legacyBlocked || legacyHasSound -> ChannelMigration.KEEP_LEGACY
        else -> ChannelMigration.REPLACE_LEGACY
    }

    /** The channel reset alerts are posted on (creating or migrating it if needed). */
    fun channelId(ctx: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return SOUND_CHANNEL_ID
        ensureChannel(ctx)
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHANNEL, null) ?: SOUND_CHANNEL_ID
    }

    @Synchronized
    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var chosen = prefs.getString(KEY_CHANNEL, null)
        if (chosen == null) {
            val legacy = nm.getNotificationChannel(LEGACY_CHANNEL_ID)
            val decision = channelMigration(
                legacyExists = legacy != null,
                legacyBlocked = legacy?.importance == NotificationManager.IMPORTANCE_NONE,
                legacyHasSound = legacy?.sound != null
            )
            chosen = if (decision == ChannelMigration.KEEP_LEGACY) LEGACY_CHANNEL_ID else SOUND_CHANNEL_ID
            if (decision == ChannelMigration.REPLACE_LEGACY) nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            prefs.edit { putString(KEY_CHANNEL, chosen) }
        }
        // Re-creating an existing channel only refreshes its name and description; user settings stay.
        val channel = NotificationChannel(chosen, "Reset Alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Notifies you when a daily game you track resets and a new puzzle is out."
        }
        nm.createNotificationChannel(channel)
        ReminderChannel.ensure(ctx, nm)
    }

    /**
     * Posts one notification per game; tapping it opens that game inside Dailies. Returns the
     * notification id it was posted under, or null if notifications are off. With [updateSummary]
     * false the caller posts the group summary itself once the whole batch is out
     * ([postGroupSummary]), so the count never depends on the system having registered each post yet.
     */
    fun postGameNotification(ctx: Context, gameId: String, title: String, body: String, updateSummary: Boolean = true): Int? {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return null

        val notificationId = notificationIdFor(ctx, gameId)
        val launch = Intent(ctx, HubActivity::class.java).apply {
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
        val notification = NotificationCompat.Builder(ctx, channelId(ctx))
            .setSmallIcon(R.drawable.ic_stat_dailes)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Not silent: the channel decides (default sound; the user can change it in Android
            // settings). Only the children alert, never the summary as well.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setGroup(GROUP_KEY)
            .build()

        return try {
            nm.notify(notificationId, notification)
            if (updateSummary) postGroupSummary(ctx, mapOf(notificationId to title))
            notificationId
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call; nothing to do.
            null
        }
    }

    /**
     * The games a group summary lists: the ones already in the shade plus [justPosted] (id -> title),
     * which the system may not report as active yet. Each id counts once; the summary itself is excluded.
     */
    fun summaryEntries(active: List<Pair<Int, CharSequence?>>, justPosted: Map<Int, String>): List<String> {
        val byId = LinkedHashMap<Int, String>()
        active.forEach { (id, title) -> if (id != SUMMARY_ID) byId[id] = title?.toString().orEmpty() }
        justPosted.forEach { (id, title) -> if (id != SUMMARY_ID) byId[id] = title }
        return byId.values.toList()
    }

    /**
     * When several games are ready at once, bundle them under one summary instead of a wall of
     * separate alerts. [justPosted] (id -> title) are the notifications this pass has just posted.
     */
    fun postGroupSummary(ctx: Context, justPosted: Map<Int, String>) {
        val nm = NotificationManagerCompat.from(ctx)
        val systemNm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val active = runCatching { systemNm.activeNotifications.toList() }.getOrDefault(emptyList())
            .filter { it.notification.group == GROUP_KEY }
            .map { it.id to it.notification.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE) }
        val entries = summaryEntries(active, justPosted)
        if (entries.size < 2) return

        val style = NotificationCompat.InboxStyle()
        entries.take(6).forEach { title -> if (title.isNotBlank()) style.addLine(title) }
        val summaryText = "${entries.size} daily games are ready"
        style.setSummaryText(summaryText)

        val launch = Intent(ctx, HubActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            ctx, SUMMARY_ID, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summary = NotificationCompat.Builder(ctx, channelId(ctx))
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
