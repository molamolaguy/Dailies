package com.dailygames.hub

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Settings → About → Report a problem (1.8.0): app crashes kept on this device so they can be attached
 * to a GitHub issue. Only the crash itself (type, message, stack) is stored; never games or scores.
 */
object CrashLog {
    private const val FILE = "crash_log.txt"
    private const val MAX_BYTES = 24 * 1024
    @Volatile private var installed = false

    /** Records uncaught exceptions, then lets Android handle them as usual. Safe to call more than once. */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { append(app, "Crash on ${thread.name}: ${describe(error)}") }
            previous?.uncaughtException(thread, error)
        }
    }

    /** "IllegalStateException: message" plus the first stack lines, and the cause's. */
    fun describe(error: Throwable): String {
        val sb = StringBuilder()
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < 3) {
            if (depth > 0) sb.append("\nCaused by: ")
            sb.append(e.javaClass.name).append(": ").append(e.message.orEmpty().take(300))
            e.stackTrace.take(if (depth == 0) 14 else 6).forEach { sb.append("\n    at ").append(it.toString()) }
            e = e.cause?.takeIf { it !== e }
            depth += 1
        }
        return sb.toString()
    }

    /** Also for things that aren't exceptions, such as the hub's WebView renderer being stopped. */
    @Synchronized
    fun append(ctx: Context, entry: String) {
        val f = File(ctx.filesDir, FILE)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        val text = (if (f.exists()) f.readText() else "") + "[$stamp] $version: $entry\n"
        f.writeText(if (text.length > MAX_BYTES) text.takeLast(MAX_BYTES).substringAfter('\n') else text)
    }

    fun read(ctx: Context): String = runCatching { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("")

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE).delete() }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(KEY_EXITS_CLEARED, System.currentTimeMillis()) }
    }

    private const val PREFS = "dailies_crash_log"
    private const val KEY_EXITS_CLEARED = "exits_cleared_at"

    /** Android 11+: recent times the app was stopped by a crash or for not responding (ANR). */
    fun recentExits(ctx: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        val since = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_EXITS_CLEARED, 0L)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return ""
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return runCatching {
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
                .filter { it.timestamp > since && it.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR) }
                .joinToString("\n") { info ->
                    val why = when (info.reason) {
                        ApplicationExitInfo.REASON_ANR -> "Not responding (ANR)"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                        else -> "Crash"
                    }
                    "[${fmt.format(Date(info.timestamp))}] $why: ${info.description.orEmpty().take(200)}"
                }
        }.getOrDefault("")
    }

    /** The report's native part, as JSON for the hub. */
    fun reportJson(ctx: Context): String {
        val crashes = listOf(read(ctx).trim(), recentExits(ctx).trim()).filter { it.isNotEmpty() }.joinToString("\n")
        return org.json.JSONObject()
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("crashes", crashes)
            .toString()
    }
}
