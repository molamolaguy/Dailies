package com.dailygames.hub

import androidx.core.content.edit
import android.content.Context
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Serialises everything that reads or writes the notification anchors: the JS sync of the games list
 * and the reset processor (alarm receiver + WorkManager backup), so neither overwrites the other.
 */
object ScheduleLock

/** Abstraction over key-value persistence for constructor-injected testing. */
interface GameStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun contains(key: String): Boolean
    fun remove(key: String)
    fun keys(): Set<String>
}

/** Android SharedPreferences implementation of [GameStorage]. */
class SharedPrefsGameStorage(context: Context) : GameStorage {
    private val prefs = context.getSharedPreferences(GameStore.PREFS, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun remove(key: String) {
        prefs.edit { remove(key) }
    }

    override fun keys(): Set<String> = prefs.all.keys.toSet()
}

/** Pure in-memory implementation of [GameStorage] for unit testing and preview states. */
class InMemoryGameStorage(initial: Map<String, String> = emptyMap()) : GameStorage {
    private val map = initial.toMutableMap()

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun remove(key: String) {
        map.remove(key)
    }

    override fun keys(): Set<String> = map.keys.toSet()
}

/**
 * Constructor-injected repository managing tracked games and per-game notification cycle keys.
 */
class GameRepository(
    private val storage: GameStorage,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val localTimeZone: TimeZone = TimeZone.getDefault()
) {
    fun load(): JSONArray {
        val raw = storage.getString(GameStore.KEY_GAMES) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    /**
     * Syncs the tracked games list from JS.
     * Newly added games or games whose resetTime/resetZone changed are re-anchored to the
     * current cycle key so editing a game's schedule never triggers a false reset notification (K8).
     */
    fun sync(json: String) = synchronized(ScheduleLock) { syncLocked(json) }

    private fun syncLocked(json: String) {
        val games = try {
            JSONArray(json)
        } catch (e: Exception) {
            return
        }

        val previousSchedules = mutableMapOf<String, Pair<String, String>>()
        val previouslyEnabled = mutableMapOf<String, Boolean>()
        val existingGames = load()
        for (i in 0 until existingGames.length()) {
            val eg = existingGames.optJSONObject(i) ?: continue
            val eid = eg.optString("id")
            if (eid.isNotEmpty()) {
                previousSchedules[eid] = eg.optString("resetTime", "00:00") to eg.optString("resetZone", "local")
                previouslyEnabled[eid] = eg.optBoolean("notifyEnabled", true)
            }
        }

        storage.putString(GameStore.KEY_GAMES, games.toString())
        val now = clock()
        val currentIds = HashSet<String>()
        for (i in 0 until games.length()) {
            val g = games.optJSONObject(i) ?: continue
            val id = g.optString("id")
            if (id.isEmpty()) continue
            currentIds += id

            val resetTime = g.optString("resetTime", "00:00")
            val resetZone = g.optString("resetZone", "local")
            val prevSchedule = previousSchedules[id]
            val scheduleChanged = prevSchedule != null &&
                (prevSchedule.first != resetTime || !prevSchedule.second.equals(resetZone, ignoreCase = true))
            // While alerts were off the anchor may not have moved for days; turning them back on must
            // not announce a puzzle that came out before that.
            val alertsReEnabled = previouslyEnabled[id] == false && g.optBoolean("notifyEnabled", true)

            if (!storage.contains(GameStore.NOTIFIED_PREFIX + id) || scheduleChanged || alertsReEnabled) {
                storage.putString(
                    GameStore.NOTIFIED_PREFIX + id,
                    GameStore.cycleKey(resetTime, resetZone, now, localTimeZone)
                )
            }
        }
        // Forget the anchors of removed games, so re-adding one (often under the same id) starts fresh
        // instead of inheriting a stale anchor that would fire a "has refreshed" for an old cycle.
        storage.keys()
            .filter { it.startsWith(GameStore.NOTIFIED_PREFIX) && it.removePrefix(GameStore.NOTIFIED_PREFIX) !in currentIds }
            .forEach { storage.remove(it) }
    }

    fun lastNotifiedKey(id: String): String? =
        storage.getString(GameStore.NOTIFIED_PREFIX + id)

    fun setNotifiedKey(id: String, key: String) {
        storage.putString(GameStore.NOTIFIED_PREFIX + id, key)
    }

    fun saveGames(games: JSONArray) {
        storage.putString(GameStore.KEY_GAMES, games.toString())
    }
}

/**
 * Native mirror of the tracked games (schedule + alert flag + lastCycleKey). The WebView UI pushes the
 * list here when games or schedules change so the background worker can notify right when the next reset occurs.
 */
object GameStore {
    const val PREFS = "daily_deck_games"
    const val KEY_GAMES = "games_json"
    const val NOTIFIED_PREFIX = "notified_"

    fun repository(ctx: Context): GameRepository =
        GameRepository(SharedPrefsGameStorage(ctx))

    fun load(ctx: Context): JSONArray = repository(ctx).load()

    /** Saves the list. Newly seen games or schedule changes are anchored to the current cycle. */
    fun sync(ctx: Context, json: String) {
        repository(ctx).sync(json)
    }

    fun lastNotifiedKey(ctx: Context, id: String): String? =
        repository(ctx).lastNotifiedKey(id)

    fun setNotifiedKey(ctx: Context, id: String, key: String) {
        repository(ctx).setNotifiedKey(id, key)
    }

    /**
     * Same definition of "which daily cycle are we in" as the JS side (getCycleDateKey):
     * uses calendar math in UTC or local time so DST transitions never shift the cycle date.
     */
    fun cycleKey(
        resetTime: String,
        resetZone: String,
        nowMs: Long = System.currentTimeMillis(),
        localTimeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val parts = resetTime.split(":")
        val h = (parts.getOrNull(0)?.toIntOrNull() ?: 0).coerceIn(0, 23)
        val m = (parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 59)
        val tz = resolveZone(resetZone, localTimeZone)
        val nowCal = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        val resetTodayCal = Calendar.getInstance(tz).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (nowCal.timeInMillis < resetTodayCal.timeInMillis) {
            nowCal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            nowCal.get(Calendar.YEAR), nowCal.get(Calendar.MONTH) + 1, nowCal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * Calculates milliseconds until the next daily puzzle reset for a game.
     */
    fun msUntilNextReset(
        resetTime: String,
        resetZone: String,
        nowMs: Long = System.currentTimeMillis(),
        localTimeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        val parts = resetTime.split(":")
        val h = (parts.getOrNull(0)?.toIntOrNull() ?: 0).coerceIn(0, 23)
        val m = (parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 59)
        val tz = resolveZone(resetZone, localTimeZone)
        // Pick the calendar day first, then set the wall-clock time on it. (Adding a day after setting
        // the time would carry a DST-adjusted time, e.g. 02:30 -> 03:30 on spring-forward day, into
        // every following day.)
        fun resetOn(dayOffset: Int): Long = Calendar.getInstance(tz).apply {
            timeInMillis = nowMs
            if (dayOffset != 0) add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = resetOn(0)
        val target = if (today > nowMs) today else resetOn(1)
        return (target - nowMs).coerceAtLeast(1_000L)
    }

    /**
     * Finds the shortest delay (in ms) until the next reset across all games that have notifications enabled (P12).
     * Returns null if no game has notifications enabled.
     */
    fun nextResetDelayMs(
        games: JSONArray,
        nowMs: Long = System.currentTimeMillis(),
        localTimeZone: TimeZone = TimeZone.getDefault()
    ): Long? {
        var minDelay: Long? = null
        for (i in 0 until games.length()) {
            val g = games.optJSONObject(i) ?: continue
            val id = g.optString("id")
            if (id.isEmpty() || !g.optBoolean("notifyEnabled", true)) continue
            val delay = msUntilNextReset(
                g.optString("resetTime", "00:00"),
                g.optString("resetZone", "local"),
                nowMs,
                localTimeZone
            )
            if (minDelay == null || delay < minDelay) {
                minDelay = delay
            }
        }
        return minDelay
    }

    /**
     * Zone a schedule is evaluated in: "local" = the device zone, "utc", or an IANA id such as
     * "America/New_York" (the zone the reset time was published in). Unknown ids fall back to local.
     * Mirrors normalizeZone() in assets/hub-core.js.
     */
    fun resolveZone(resetZone: String?, localTimeZone: TimeZone = TimeZone.getDefault()): TimeZone {
        val raw = resetZone?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("local", ignoreCase = true)) return localTimeZone
        if (raw.lowercase(Locale.US) in UTC_ALIASES) return TimeZone.getTimeZone("UTC")
        if (!raw.contains('/')) return localTimeZone
        val tz = TimeZone.getTimeZone(raw)
        // getTimeZone() silently returns GMT for ids it doesn't know.
        return if (tz.id == raw) tz else localTimeZone
    }

    private val UTC_ALIASES = setOf("utc", "gmt", "z", "zulu", "etc/utc", "etc/gmt", "etc/uct", "uct", "etc/zulu", "universal", "etc/universal")

    /**
     * Formats a game's next reset in the device's local time zone (e.g. "7:00 PM"), whatever zone
     * the schedule itself is stored in.
     */
    fun formatLocalResetTime(
        resetTime: String,
        resetZone: String,
        localTimeZone: TimeZone = TimeZone.getDefault(),
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val next = nowMs + msUntilNextReset(resetTime, resetZone, nowMs, localTimeZone)
        val localCal = Calendar.getInstance(localTimeZone).apply { timeInMillis = next }
        val localH = localCal.get(Calendar.HOUR_OF_DAY)
        val localM = localCal.get(Calendar.MINUTE)
        val period = if (localH >= 12) "PM" else "AM"
        val h12 = if (localH % 12 == 0) 12 else localH % 12
        return String.format(Locale.US, "%d:%02d %s", h12, localM, period)
    }
}
