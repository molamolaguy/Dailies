package com.dailygames.hub

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Durable, size-unbounded storage for the hub's full JSON state (games, every day of history and
 * every logged result). Replaces WebView localStorage, which is capped at roughly 5 MB per origin.
 *
 * Safety rules:
 *  - Writes are atomic: new JSON goes to a temp file (fsync), the current file is copied to `.bak.tmp`
 *    (fsync) and renamed to `.bak`, then the temp file is renamed directly over `hub_state.json`
 *    so `hub_state.json` stays continuously present on disk. The previous good copy always survives a
 *    failed write.
 *  - Reads fall back to `.bak` when the main file can't be read or fails JSON validation. If neither
 *    can be read, [read] returns [READ_ERROR] (not ""), so the hub knows data exists and refuses to
 *    overwrite it with an empty state.
 *  - A failed write is re-queued and retried on the next save; [onWriteResult] reports failures
 *    (false) and the first success after them (true) so the hub can tell the user.
 */
class HubStateStore(
    private val file: File,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hub-state-writer").apply { isDaemon = true }
    },
    private val onWriteResult: ((Boolean) -> Unit)? = null
) {
    private val pending = AtomicReference<String?>(null)
    /** True while a flush task is queued on [executor] and hasn't started yet. */
    private val flushScheduled = AtomicBoolean(false)
    @Volatile private var lastWriteFailed = false
    private val lock = Any()
    private val backup get() = File(file.parentFile, file.name + ".bak")
    private val backupTemp get() = File(file.parentFile, file.name + ".bak.tmp")
    private val temp get() = File(file.parentFile, file.name + ".tmp")

    /** The stored JSON; "" when nothing has ever been saved; [READ_ERROR] when data exists but can't be read. */
    fun read(): String = synchronized(lock) {
        pending.get()?.let { return it }
        return readSnapshot(file)
    }

    /** Queues [json] to be written; returns immediately. Blank input is ignored (never wipes data). */
    fun write(json: String) {
        if (json.isBlank()) return
        pending.set(json)
        // One queued task picks up the newest JSON. Tracked separately from `pending`, which also
        // holds a failed write waiting for its retry: that must not stop this save from scheduling one.
        if (!flushScheduled.compareAndSet(false, true)) return
        try {
            executor.execute {
                flushScheduled.set(false)
                flush()
            }
        } catch (e: Exception) {
            flushScheduled.set(false)
            flush() // executor shut down (activity finishing): write synchronously
        }
    }

    /** Writes any queued JSON now on the calling thread. */
    fun flush() {
        synchronized(lock) {
            val json = pending.getAndSet(null) ?: return
            if (!writeAtomically(json)) {
                // Keep it queued (unless something newer arrived) so the next save retries.
                pending.compareAndSet(null, json)
                lastWriteFailed = true
                runCatching { onWriteResult?.invoke(false) }
            } else if (lastWriteFailed) {
                lastWriteFailed = false
                runCatching { onWriteResult?.invoke(true) }
            }
        }
    }

    /** Flushes on the store's own writer thread (e.g. from onPause) without blocking the caller. */
    fun flushAsync() {
        try {
            executor.execute { flush() }
        } catch (e: Exception) {
            flush()
        }
    }

    /**
     * Moves an unreadable state aside (`hub_state.unreadable-<time>.json`) so the hub can start fresh
     * without ever deleting the user's data.
     */
    fun quarantine(): Boolean = synchronized(lock) {
        synchronized(fileLock) {
            var ok = true
            val stamp = System.currentTimeMillis()
            listOf(file, backup).forEach { f ->
                if (f.exists()) {
                    val dest = File(f.parentFile, "${file.nameWithoutExtension}.unreadable-$stamp-${f.extension}.json")
                    ok = f.renameTo(dest) && ok
                }
            }
            ok
        }
    }

    /** Stops the writer thread after flushing, waiting briefly for an in-flight write. */
    fun close() {
        flush()
        executor.shutdown()
        runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    private fun writeAtomically(json: String): Boolean = synchronized(fileLock) {
        val dir = file.parentFile ?: return false
        if (!dir.exists()) dir.mkdirs()
        val tmp = temp
        val bak = backup
        val bakTmp = backupTemp
        try {
            FileOutputStream(tmp).use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            // Keep `file` continuously present: copy `file` -> `.bak.tmp` (fsync) -> rename to `.bak`,
            // then atomically rename `tmp` directly over `file`.
            if (file.exists()) {
                if (!file.isFile) return false
                if (bakTmp.exists() && !bakTmp.delete()) return false
                FileInputStream(file).use { input ->
                    FileOutputStream(bakTmp).use { out ->
                        input.copyTo(out)
                        out.flush()
                        out.fd.sync()
                    }
                }
                if (!replaceFile(bakTmp, bak)) {
                    runCatching { bakTmp.delete() }
                    return false
                }
                syncDirectory(dir)
            }
            if (!replaceFile(tmp, file)) {
                return false
            }
            syncDirectory(dir)
            return true
        } catch (e: Throwable) {
            return false
        } finally {
            if (bakTmp.exists()) runCatching { bakTmp.delete() }
            if (tmp.exists() && file.exists()) runCatching { tmp.delete() }
        }
    }

    /** Replaces [dest] with [src], using POSIX atomic rename and falling back if rename-over-existing fails. */
    private fun replaceFile(src: File, dest: File): Boolean {
        if (src.renameTo(dest)) return true
        // POSIX rename(2) atomically overwrites an existing destination file.
        val posixReplaced = runCatching {
            android.system.Os.rename(src.path, dest.path)
            true
        }.getOrDefault(false)
        if (posixReplaced) return true
        if (dest.exists() && !dest.delete()) return false
        return src.renameTo(dest)
    }

    /** fsync the directory so the rename itself survives a power loss (best effort). */
    private fun syncDirectory(dir: File) {
        runCatching {
            val fd = android.system.Os.open(dir.path, android.system.OsConstants.O_RDONLY, 0)
            try {
                android.system.Os.fsync(fd)
            } finally {
                android.system.Os.close(fd)
            }
        }
    }

    companion object {
        const val FILE_NAME = "hub_state.json"
        const val READ_ERROR = "__DAILES_READ_ERROR__"
        /** Refuse to load anything absurd (a corrupt file would otherwise exhaust memory). */
        private const val MAX_STATE_BYTES = 200L * 1024 * 1024
        /** Coordinates reads (including AutoBackup snapshots) and atomic swaps across threads/instances. */
        private val fileLock = Any()

        /** Reads persisted state (falling back to `.bak`) without creating a writer executor. */
        fun readSnapshot(file: File): String = synchronized(fileLock) {
            val backup = File(file.parentFile, file.name + ".bak")
            val main = readFile(file)
            if (main != null && looksComplete(main)) return main
            val bak = readFile(backup)
            if (bak != null && looksComplete(bak)) return bak
            // Neither looks whole: hand over whatever there is and let the hub decide (it shows the
            // "couldn't be read" banner instead of overwriting it).
            if (main != null && main.isNotBlank()) return main
            if (bak != null && bak.isNotBlank()) return bak
            val anyExists = (file.exists() && file.length() > 0) || (backup.exists() && backup.length() > 0)
            return if (anyExists) READ_ERROR else ""
        }

        /**
         * A saved state must parse cleanly as a JSON object (and not have trailing garbage or NUL bytes).
         * If it lacks a `"games"` array, treat an empty/corrupt shell `{}` as incomplete so a healthy
         * `.bak` can rescue it, while still accepting minimal JSON objects in legacy/unit tests when `.bak`
         * does not exist.
         */
        private fun looksComplete(text: String): Boolean {
            val t = text.trim()
            if (t.length < 2 || t.first() != '{' || t.last() != '}' || t.indexOf('\u0000') >= 0) return false
            val tok = org.json.JSONTokener(t)
            val parsed = try {
                tok.nextValue()
            } catch (_: Throwable) {
                return false
            }
            val obj = parsed as? JSONObject ?: return false
            if (tok.nextClean() != '\u0000') return false
            if (obj.length() == 0) return false
            if (obj.has("games") && obj.optJSONArray("games") == null) return false
            return true
        }

        private fun readFile(f: File): String? {
            if (!f.exists() || !f.isFile) return null
            if (f.length() > MAX_STATE_BYTES) return null
            return try {
                f.readText(Charsets.UTF_8)
            } catch (e: Throwable) {
                // IOException, or OutOfMemoryError on a huge/corrupt file: report "unreadable", never crash.
                null
            }
        }
    }
}

