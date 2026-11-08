package com.dailygames.hub

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Durable, size-unbounded storage for the hub's full JSON state (games, every day of history and
 * every logged result). Replaces WebView localStorage, which is capped at roughly 5 MB per origin.
 *
 * Safety rules:
 *  - Writes are atomic: new JSON goes to a temp file (fsync), the current file becomes `.bak`, then
 *    the temp file is renamed into place. The previous good copy always survives a failed write.
 *  - Reads fall back to `.bak`. If neither can be read, [read] returns [READ_ERROR] (not ""), so the
 *    hub knows data exists and refuses to overwrite it with an empty state.
 *  - A failed write is re-queued and retried on the next save.
 */
class HubStateStore(
    private val file: File,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hub-state-writer").apply { isDaemon = true }
    }
) {
    private val pending = AtomicReference<String?>(null)
    private val lock = Any()
    private val backup get() = File(file.parentFile, file.name + ".bak")
    private val temp get() = File(file.parentFile, file.name + ".tmp")

    /** The stored JSON; "" when nothing has ever been saved; [READ_ERROR] when data exists but can't be read. */
    fun read(): String = synchronized(lock) {
        pending.get()?.let { return it }
        val main = readFile(file)
        if (main != null && main.isNotBlank()) return main
        val bak = readFile(backup)
        if (bak != null && bak.isNotBlank()) return bak
        val anyExists = (file.exists() && file.length() > 0) || (backup.exists() && backup.length() > 0)
        return if (anyExists) READ_ERROR else ""
    }

    private fun readFile(f: File): String? {
        if (!f.exists()) return null
        if (f.length() > MAX_STATE_BYTES) return null
        return try {
            f.readText(Charsets.UTF_8)
        } catch (e: Throwable) {
            // IOException, or OutOfMemoryError on a huge/corrupt file: report "unreadable", never crash.
            null
        }
    }

    /** Queues [json] to be written; returns immediately. Blank input is ignored (never wipes data). */
    fun write(json: String) {
        if (json.isBlank()) return
        if (pending.getAndSet(json) != null) return // a write is already queued; it will pick this up
        try {
            executor.execute { flush() }
        } catch (e: Exception) {
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

    /** Stops the writer thread after flushing, waiting briefly for an in-flight write. */
    fun close() {
        flush()
        executor.shutdown()
        runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    private fun writeAtomically(json: String): Boolean {
        val dir = file.parentFile ?: return false
        if (!dir.exists()) dir.mkdirs()
        val tmp = temp
        try {
            FileOutputStream(tmp).use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            // Current good copy -> .bak (replacing the older backup), then temp -> current.
            if (file.exists()) {
                val bak = backup
                if (bak.exists() && !bak.delete()) return false
                if (!file.renameTo(bak)) return false
            }
            if (!tmp.renameTo(file)) {
                // Put the previous copy back; the temp file is kept for the retry.
                backup.takeIf { it.exists() && !file.exists() }?.renameTo(file)
                return false
            }
            syncDirectory(dir)
            return true
        } catch (e: Throwable) {
            if (!file.exists()) backup.takeIf { it.exists() }?.renameTo(file)
            return false
        } finally {
            if (tmp.exists() && file.exists()) tmp.delete()
        }
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
    }
}
