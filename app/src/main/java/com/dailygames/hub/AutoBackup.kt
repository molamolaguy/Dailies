package com.dailygames.hub

import androidx.core.content.edit
import androidx.core.net.toUri
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Optional scheduled backup: once a week a `.json` backup (the same format as Save Backup) is written to
 * a folder the user picked with the system folder picker. The newest [KEEP] automatic backups are kept.
 * Nothing leaves the phone unless the chosen folder is itself synced by another app.
 */
object AutoBackup {
    private const val PREFS = "dailes_auto_backup"
    private const val KEY_TREE = "tree_uri"
    private const val KEY_LAST_AT = "last_at"
    private const val KEY_LAST_NAME = "last_name"
    private const val KEY_LAST_ERROR = "last_error"
    private const val WORK_NAME = "dailies_auto_backup"
    const val FILE_PREFIX = "dailies_autobackup_"
    const val KEEP = 5
    const val INTERVAL_MS = 7L * 24 * 60 * 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folder(ctx: Context): Uri? = prefs(ctx).getString(KEY_TREE, null)?.toUri()

    /** Status for the Settings card: {enabled, folder, lastAt, lastName, error}. */
    fun statusJson(ctx: Context): String {
        val p = prefs(ctx)
        val tree = folder(ctx)
        return JSONObject()
            .put("enabled", tree != null)
            .put("folder", tree?.let { folderLabel(it) } ?: "")
            .put("lastAt", p.getLong(KEY_LAST_AT, 0L))
            .put("lastName", p.getString(KEY_LAST_NAME, "") ?: "")
            .put("error", p.getString(KEY_LAST_ERROR, "") ?: "")
            .toString()
    }

    /** "Download/Dailies" from a tree URI like primary:Download/Dailies. */
    fun folderLabel(tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return ""
        val path = id.substringAfter(':', id)
        return path.ifBlank { "Internal storage" }
    }

    /** Saves the folder (keeping access across reboots) and turns weekly backups on. */
    fun enable(ctx: Context, tree: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        folder(ctx)?.takeIf { it != tree }?.let { old -> releaseAccess(ctx, old) }
        prefs(ctx).edit { putString(KEY_TREE, tree.toString()); remove(KEY_LAST_ERROR) }
        schedule(ctx)
    }

    fun disable(ctx: Context) {
        folder(ctx)?.let { releaseAccess(ctx, it) }
        prefs(ctx).edit { clear() }
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }

    private fun releaseAccess(ctx: Context, tree: Uri) {
        runCatching {
            ctx.contentResolver.releasePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    /** A daily check; it only writes when the last backup is a week old. */
    fun schedule(ctx: Context) {
        if (folder(ctx) == null) return
        val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun isDue(lastAt: Long, nowMs: Long): Boolean = lastAt <= 0L || nowMs - lastAt >= INTERVAL_MS - 60 * 60_000L || nowMs < lastAt

    /** The backup file's content, in the same format as the hub's Save Backup. */
    fun payload(stateJson: String, appVersion: String, now: Date): String? {
        val state = runCatching { JSONObject(stateJson) }.getOrNull() ?: return null
        if (!state.has("games")) return null
        return JSONObject()
            .put("app", "Dailies")
            .put("version", appVersion)
            .put("schemaVersion", state.opt("schemaVersion") ?: JSONObject.NULL)
            .put("exportedAtLocal", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now))
            .put("automatic", true)
            .put("settings", state.opt("settings") ?: JSONObject())
            .put("games", state.opt("games"))
            .put("retainedGameStats", state.opt("retainedGameStats") ?: JSONObject())
            .put("historyLogs", state.opt("historyLogs") ?: org.json.JSONArray())
            .toString(2)
    }

    fun fileName(now: Date): String = FILE_PREFIX + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(now) + ".json"

    /** Names to delete so only the newest [keep] automatic backups stay (names sort by date). */
    fun namesToPrune(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter { it.startsWith(FILE_PREFIX) && it.endsWith(".json") }.sortedDescending().drop(keep)

    /** Writes a backup now if one is due (or [force]). Returns true when a file was written. */
    @Synchronized
    fun runIfDue(ctx: Context, force: Boolean = false, nowMs: Long = System.currentTimeMillis()): Boolean {
        val tree = folder(ctx) ?: return false
        val p = prefs(ctx)
        if (!force && !isDue(p.getLong(KEY_LAST_AT, 0L), nowMs)) return false
        val now = Date(nowMs)
        val state = HubStateStore(File(ctx.filesDir, HubStateStore.FILE_NAME)).read()
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
        val content = payload(state, version, now)
        if (content == null) {
            // Nothing saved yet (or unreadable): no backup, but not an error worth showing.
            return false
        }
        val name = fileName(now)
        return try {
            val resolver = ctx.contentResolver
            val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(resolver, dir, "application/json", name)
                ?: throw IllegalStateException("The folder refused a new file")
            resolver.openOutputStream(doc, "wt")?.use { it.write(content.toByteArray()) }
                ?: throw IllegalStateException("Couldn't write the file")
            prune(ctx, tree)
            p.edit { putLong(KEY_LAST_AT, nowMs); putString(KEY_LAST_NAME, name); remove(KEY_LAST_ERROR) }
            true
        } catch (e: Exception) {
            val msg = if (e is SecurityException) "Dailies lost access to the backup folder. Choose it again."
            else "The last automatic backup failed. Check the folder still exists."
            p.edit { putString(KEY_LAST_ERROR, msg) }
            false
        }
    }

    private fun prune(ctx: Context, tree: Uri) {
        val resolver = ctx.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val byName = HashMap<String, String>()
        runCatching {
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) byName[c.getString(1) ?: continue] = c.getString(0)
            }
        }
        namesToPrune(byName.keys.toList()).forEach { n ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, byName[n])) }
        }
    }
}

class AutoBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        AutoBackup.runIfDue(applicationContext)
        return Result.success()
    }
}
