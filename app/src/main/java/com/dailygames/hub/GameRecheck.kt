package com.dailygames.hub

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checking game pages for changes (1.8.5, off unless turned on in Settings). About once a week, on
 * Wi-Fi with the battery not low, each game's page is read again the same way as when it was added
 * (without running its scripts, which needs the app open). What was found is saved here and nothing
 * is changed: the next time the app is opened, the hub compares it with the games and asks first.
 */
object GameRecheck {
    private const val PREFS = "dailies_game_recheck"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_AT = "last_at"
    private const val WORK_NAME = "dailies_game_recheck"
    const val FILE_NAME = "game_recheck.json"
    const val INTERVAL_MS = 7L * 24 * 60 * 60_000L
    /** Most pages read in one pass, and the longest a pass may take (a worker gets about 10 minutes). */
    const val MAX_GAMES = 60
    const val PASS_BUDGET_MS = 7L * 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit { putBoolean(KEY_ENABLED, on) }
        if (on) schedule(ctx) else {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
            clearResults(ctx)
        }
    }

    /** A daily check on Wi-Fi; it only reads the pages when the last pass is a week old. */
    fun schedule(ctx: Context) {
        if (!enabled(ctx)) return
        val req = PeriodicWorkRequestBuilder<GameRecheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun isDue(lastAt: Long, nowMs: Long): Boolean = lastAt <= 0L || nowMs < lastAt || nowMs - lastAt >= INTERVAL_MS - 60 * 60_000L

    /** The games worth reading again: an id and a web address (http or https). */
    fun targets(games: JSONArray, max: Int = MAX_GAMES): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        for (i in 0 until games.length()) {
            val g = games.optJSONObject(i) ?: continue
            val id = g.optString("id")
            val url = g.optString("url").trim()
            if (id.isEmpty() || !(url.startsWith("https://", true) || url.startsWith("http://", true)) || !seen.add(id)) continue
            out += id to url
            if (out.size >= max) break
        }
        return out
    }

    /**
     * Reads every game's page again and saves what was found: {at, results: [{id, url, ok, finalUrl, parsed}]}.
     * [scrape] is [WebsiteScraper.fetchAndScrape] (swapped in tests). Returns how many pages were read.
     */
    @Synchronized
    fun run(
        ctx: Context,
        force: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        scrape: (String) -> JSONObject = { WebsiteScraper.fetchAndScrape(it) }
    ): Int {
        if (!force && !isDue(prefs(ctx).getLong(KEY_LAST_AT, 0L), nowMs)) return 0
        val results = collect(targets(GameStore.load(ctx)), scrape)
        // Saved even when empty, so the hub knows a pass happened (and found nothing to suggest).
        val out = JSONObject().put("at", nowMs).put("results", results)
        val f = file(ctx)
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(out.toString())
        if (!tmp.renameTo(f)) { f.writeText(out.toString()); tmp.delete() }
        prefs(ctx).edit { putLong(KEY_LAST_AT, nowMs) }
        return results.length()
    }

    /** Reads each target's page in turn, within [PASS_BUDGET_MS]. A page that fails is left out. */
    fun collect(
        targets: List<Pair<String, String>>,
        scrape: (String) -> JSONObject,
        budgetMs: Long = PASS_BUDGET_MS,
        clock: () -> Long = { System.currentTimeMillis() }
    ): JSONArray {
        val started = clock()
        val results = JSONArray()
        for ((id, url) in targets) {
            if (clock() - started > budgetMs) break
            val res = runCatching { scrape(url) }.getOrNull() ?: continue
            if (!res.optBoolean("ok", false)) continue
            val parsed = res.optJSONObject("parsed") ?: continue
            results.put(
                JSONObject()
                    .put("id", id)
                    .put("url", url)
                    .put("finalUrl", res.optString("finalUrl", url))
                    .put("parsed", parsed)
            )
        }
        return results
    }

    /** What the last pass found, for the hub to compare and ask about ("" when there's nothing new). */
    fun results(ctx: Context): String = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    /** The hub has asked about (or set aside) what was found. */
    fun clearResults(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}

class GameRecheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!GameRecheck.enabled(applicationContext)) return Result.success()
        runCatching { GameRecheck.run(applicationContext) }
            .onFailure { runCatching { CrashLog.append(applicationContext, "Checking game pages failed: ${it.javaClass.simpleName}") } }
        return Result.success()
    }
}
