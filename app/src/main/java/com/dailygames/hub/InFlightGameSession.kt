package com.dailygames.hub

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Persists an in-progress [GameActivity] session atomically so play time, captured results, and
 * earlier switched games survive if a notification tap clears [GameActivity] via `CLEAR_TOP`
 * before `setResult` is delivered back to [HubActivity].
 */
object InFlightGameSession {
    const val FILE_NAME = "inflight_game_session.json"
    private const val MAX_JSON_BYTES = 1024 * 1024L
    private val lock = Any()

    data class Snapshot(
        val gameId: String,
        val openLog: Boolean,
        val elapsedMs: Long,
        val openedAt: Long,
        val shareText: String,
        val shareConfirmed: Boolean,
        val earlierJson: String
    ) {
        fun toJson(): String = JSONObject()
            .put("gameId", gameId)
            .put("openLog", openLog)
            .put("elapsedMs", elapsedMs)
            .put("openedAt", openedAt)
            .put("shareText", shareText)
            .put("shareConfirmed", shareConfirmed)
            .put("earlierJson", earlierJson)
            .toString()

        companion object {
            fun fromJson(raw: String?): Snapshot? {
                if (raw.isNullOrBlank()) return null
                val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
                val id = obj.optString("gameId").trim()
                if (id.isEmpty()) return null
                return Snapshot(
                    gameId = id,
                    openLog = obj.optBoolean("openLog", false),
                    elapsedMs = obj.optLong("elapsedMs", 0L).coerceAtLeast(0L),
                    openedAt = obj.optLong("openedAt", 0L).coerceAtLeast(0L),
                    shareText = obj.optString("shareText", ""),
                    shareConfirmed = obj.optBoolean("shareConfirmed", false),
                    earlierJson = obj.optString("earlierJson", "")
                )
            }
        }
    }

    private fun sessionFile(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    fun save(ctx: Context, snapshot: Snapshot): Boolean = saveToFile(sessionFile(ctx), snapshot)

    fun consume(ctx: Context): Snapshot? = consumeFromFile(sessionFile(ctx))

    fun clear(ctx: Context) {
        clearFile(sessionFile(ctx))
    }

    internal fun saveToFile(file: File, snapshot: Snapshot): Boolean = synchronized(lock) {
        if (snapshot.gameId.isBlank()) return false
        val dir = file.parentFile ?: return false
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        return try {
            val bytes = snapshot.toJson().toByteArray(Charsets.UTF_8)
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (tmp.renameTo(file)) return true
            val posixReplaced = runCatching {
                android.system.Os.rename(tmp.path, file.path)
                true
            }.getOrDefault(false)
            if (posixReplaced) return true
            if (file.exists() && !file.delete()) return false
            tmp.renameTo(file)
        } catch (_: Throwable) {
            false
        } finally {
            if (tmp.exists() && file.exists()) runCatching { tmp.delete() }
        }
    }

    internal fun consumeFromFile(file: File): Snapshot? = synchronized(lock) {
        if (!file.exists() || !file.isFile) return null
        val snap = try {
            if (file.length() in 1L..MAX_JSON_BYTES) {
                Snapshot.fromJson(file.readText(Charsets.UTF_8))
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { file.delete() }
            runCatching { File(file.parentFile, file.name + ".tmp").delete() }
        }
        snap
    }

    internal fun clearFile(file: File) = synchronized(lock) {
        runCatching { file.delete() }
        runCatching { File(file.parentFile, file.name + ".tmp").delete() }
        Unit
    }
}
