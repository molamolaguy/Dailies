package com.dailygames.hub

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Settings → Data & Backup → Check links & icons, and Storage (1.8.0).
 *
 * - [checkLinks]: does each game's page still load? Gone (404/410), erroring, unreachable, moved to
 *   another site, or blocked by a bot check.
 * - [refreshIcons]: looks for the sharpest icon each site offers (apple-touch-icon, the page's own
 *   icon, the site's /apple-touch-icon.png) and keeps it only if it's bigger than the current one.
 * - [storageInfo] / [clearStorage]: the caches Dailies keeps, by kind.
 */
object GameHealth {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 Dailies"

    data class Game(val id: String, val url: String, val icon: String)

    fun parseGames(json: String): List<Game> = runCatching {
        val arr = JSONArray(json)
        (0 until minOf(arr.length(), 300)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) null
            else Game(o.optString("id"), url, o.optString("icon"))
        }
    }.getOrDefault(emptyList())

    private const val PASS_DEADLINE_MS = 60_000L

    private fun <T> inParallel(items: List<Game>, passBudgetMs: Long = PASS_DEADLINE_MS, work: (Game) -> T): List<T?> {
        if (items.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(4, items.size))
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(passBudgetMs)
        return try {
            val futures = items.map { g -> pool.submit(Callable { runCatching { work(g) }.getOrNull() }) }
            futures.map { f ->
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())
                if (remainingMs <= 0L) {
                    f.cancel(true)
                    null
                } else {
                    runCatching { f.get(minOf(25_000L, remainingMs), TimeUnit.MILLISECONDS) }.getOrNull()
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- Dead links ----

    /** Pure: what a fetch result means for a game link. */
    fun classify(originalUrl: String, code: Int?, finalUrl: String?, botCheck: Boolean): Pair<String, String?> {
        if (code == null) return "unreachable" to null
        if (botCheck || code == 401 || code == 403 || code == 429) return "blocked" to null
        if (code == 404 || code == 410) return "dead" to null
        if (code >= 400) return "error" to null
        val from = siteOf(originalUrl)
        val to = finalUrl?.let { siteOf(it) }
        if (to != null && from != null && to != from) return "moved" to finalUrl
        return "ok" to null
    }

    /** The site of a URL without leading "www." or "m.". */
    fun siteOf(url: String): String? = runCatching {
        URI(url).host?.lowercase(Locale.US)?.removePrefix("www.")?.removePrefix("m.")
    }.getOrNull()

    fun checkLinks(json: String): String {
        val games = parseGames(json)
        val results = inParallel(games) { g ->
            val resp = SafeHttp.get(g.url, mapOf("User-Agent" to UA, "Accept" to "text/html,application/xhtml+xml"), maxBytes = 64 * 1024, deadlineMs = 12_000)
            val bot = resp != null && resp.code in 200..299 && WebsiteScraper.looksLikeBotChallenge(String(resp.body, Charsets.UTF_8))
            // A 403/503 from a bot wall still means the site is there.
            val wall = resp != null && resp.code in setOf(403, 503) && (resp.headers["server"].orEmpty().contains("cloudflare", true) || resp.headers.containsKey("cf-ray"))
            val (status, moved) = classify(g.url, resp?.code, resp?.finalUrl, bot || wall)
            JSONObject().put("id", g.id).put("status", status).put("code", resp?.code ?: 0).apply { if (moved != null) put("finalUrl", moved) }
        }
        val out = JSONArray()
        results.forEachIndexed { i, r -> out.put(r ?: JSONObject().put("id", games[i].id).put("status", "unreachable").put("code", 0)) }
        return out.toString()
    }

    // ---- Icons ----

    /** Pixel width of an image (valid SVG counts as large: it scales), or 0 if it can't be read. */
    fun iconWidth(bytes: ByteArray, mime: String): Int {
        if (bytes.isEmpty()) return 0
        if (mime.contains("svg", ignoreCase = true)) {
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8)
            return if (head.contains("<svg", ignoreCase = true) && FaviconCache.isSafeSvg(bytes)) 512 else 0
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return maxOf(0, minOf(opts.outWidth, opts.outHeight))
    }

    /** Pure: the best candidate, if it beats the current icon's size. */
    fun pickBetter(currentWidth: Int, candidates: List<Pair<String, Int>>): String? {
        val best = candidates.filter { it.second > 0 }.maxByOrNull { it.second } ?: return null
        return if (best.second > currentWidth) best.first else null
    }

    fun isGoogleS2Fallback(url: String): Boolean = url.contains("google.com/s2/favicons", ignoreCase = true)

    fun originOf(url: String): String? = runCatching {
        val u = URI(url)
        val scheme = u.scheme?.lowercase(Locale.US) ?: return@runCatching null
        val host = u.host ?: return@runCatching null
        val defaultPort = if (scheme == "https") 443 else 80
        val portPart = if (u.port > 0 && u.port != defaultPort) ":${u.port}" else ""
        "$scheme://$host$portPart"
    }.getOrNull()

    fun refreshIcons(json: String, cache: FaviconCache?): String {
        val games = parseGames(json)
        val results = inParallel(games) { g ->
            val width = { url: String -> FaviconCache.download(url)?.let { (b, m) -> iconWidth(b, m) } ?: 0 }
            val isS2 = isGoogleS2Fallback(g.icon)
            val current = if (g.icon.startsWith("http", true) && !isS2) width(g.icon) else 0
            val scraped = runCatching { WebsiteScraper.fetchAndScrape(g.url, fetchExtras = false).optJSONObject("parsed")?.optString("faviconUrl") }.getOrNull().orEmpty()
            val origin = originOf(g.url)
            val candidates = listOfNotNull(
                scraped.takeIf { it.startsWith("http", true) && !isGoogleS2Fallback(it) },
                origin?.let { "$it/apple-touch-icon.png" }
            ).distinct().filter { it != g.icon }.map { it to width(it) }
            val better = pickBetter(current, candidates)
            // Same address but the cached copy may be an old, smaller one: fetch it again next time.
            if (better == null && current > 0) cache?.forget(g.icon)
            JSONObject().put("id", g.id).apply { if (better != null) put("icon", better) }
        }
        val out = JSONArray()
        results.forEach { r -> if (r != null) out.put(r) }
        return out.toString()
    }

    // ---- Storage ----

    private fun size(f: File): Long = when {
        !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.listFiles()?.sumOf { size(it) } ?: 0L
    }

    private fun tempFiles(ctx: Context): List<File> =
        listOf(File(ctx.cacheDir, "shared")) + (ctx.cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("pending_") } ?: emptyList())

    private val OWN_CACHE_DIRS = setOf("pagecache", "updates", "shared")

    /** Bytes by kind: pages, icons, web (the WebView's own cache), updates, temp; and data (your games). */
    fun storageInfo(ctx: Context): String {
        val pages = size(File(ctx.cacheDir, "pagecache"))
        val updates = size(File(ctx.cacheDir, "updates"))
        val temp = tempFiles(ctx).sumOf { size(it) }
        val web = (ctx.cacheDir.listFiles() ?: emptyArray()).filter { !(it.isDirectory && it.name in OWN_CACHE_DIRS) && !(it.isFile && it.name.startsWith("pending_")) }.sumOf { size(it) }
        val icons = size(File(ctx.filesDir, "favicons"))
        val data = size(File(ctx.filesDir, HubStateStore.FILE_NAME))
        val parts = JSONObject().put("pages", pages).put("icons", icons).put("web", web).put("updates", updates).put("temp", temp)
        return JSONObject().put("parts", parts).put("data", data).toString()
    }

    /** Clears the chosen kinds (not "web": the caller clears the WebView cache on the main thread). */
    fun clearFiles(ctx: Context, kinds: Set<String>) {
        if ("pages" in kinds) PageCache.clearAll(ctx)
        if ("icons" in kinds) File(ctx.filesDir, "favicons").listFiles()?.forEach { it.deleteRecursively() }
        if ("updates" in kinds) UpdateInstaller.clearDownloads(ctx)
        if ("temp" in kinds) tempFiles(ctx).forEach { it.deleteRecursively() }
        if ("web" in kinds) {
            // What WebView.clearCache leaves: anything else in the cache folder that isn't Dailies' own.
            (ctx.cacheDir.listFiles() ?: emptyArray()).filter {
                !(it.isDirectory && (it.name in OWN_CACHE_DIRS || it.name == "WebView" || it.name == "org.chromium.android_webview")) &&
                    !(it.isFile && it.name.startsWith("pending_"))
            }.forEach { it.deleteRecursively() }
        }
    }

    fun totalOf(json: String, kinds: Set<String>): Long = runCatching {
        val parts = JSONObject(json).getJSONObject("parts")
        kinds.sumOf { parts.optLong(it, 0L) }
    }.getOrDefault(0L)
}
