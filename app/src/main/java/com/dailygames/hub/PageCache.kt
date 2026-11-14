package com.dailygames.hub

import android.content.Context
import androidx.core.content.edit
import android.net.ConnectivityManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Preloaded games (1.6.2). When a game's new puzzle comes out, the files its page needs (scripts,
 * stylesheets, fonts) are downloaded in the background on Wi-Fi, so opening it later is quicker. The
 * page itself always comes from the site (logins and today's puzzle stay right); only those files are
 * served from here, and only after the ad blocker has let them through. A game's files are deleted
 * when it's logged, and expire at its next reset anyway.
 */
object PageCache {
    private const val DIR = "pagecache"
    private const val MAX_FILES = 40
    private const val MAX_FILE_BYTES = 4 * 1024 * 1024
    private const val MAX_GAME_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 80L * 1024 * 1024
    private const val WORK_NAME = "page_cache_preload"
    private const val PREFS = "dailes_game_prefs"
    private const val KEY_ENABLED = "preload_games"

    class Entry(val file: File, val mime: String, val encoding: String?, val headers: Map<String, String>)
    class Index(val gameId: String, val expiresAt: Long, val entries: Map<String, Entry>)

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, on) }
        if (!on) clearAll(ctx)
    }

    private fun root(ctx: Context) = File(ctx.cacheDir, DIR)
    private fun gameDir(ctx: Context, gameId: String) = File(root(ctx), sha(gameId).take(24))
    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Deletes one game's files (it was logged, removed, or its puzzle changed). */
    fun clear(ctx: Context, gameId: String) {
        runCatching { gameDir(ctx, gameId).deleteRecursively() }
    }

    fun clearAll(ctx: Context) {
        runCatching { root(ctx).deleteRecursively() }
    }

    // ---- Downloading -----------------------------------------------------------------------

    /** Queues preloading for games whose puzzle just reset (runs on an unmetered network). */
    fun enqueue(ctx: Context, gameIds: Collection<String>) {
        if (gameIds.isEmpty() || !enabled(ctx)) return
        // The old puzzle's files go now, whether or not the new ones can be fetched.
        gameIds.forEach { clear(ctx, it) }
        val req = OneTimeWorkRequestBuilder<PreloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
            .setInputData(workDataOf("ids" to gameIds.toTypedArray()))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    class PreloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val ids = inputData.getStringArray("ids")?.toSet().orEmpty()
            if (ids.isEmpty() || !enabled(applicationContext)) return Result.success()
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm?.isActiveNetworkMetered == true) return Result.success()
            val games = GameStore.load(applicationContext)
            val now = System.currentTimeMillis()
            for (i in 0 until games.length()) {
                val g = games.optJSONObject(i) ?: continue
                val id = g.optString("id")
                if (id !in ids) continue
                val url = g.optString("url")
                val expires = now + GameStore.msUntilNextReset(g.optString("resetTime", "00:00"), g.optString("resetZone", "local"), now)
                runCatching { preload(applicationContext, id, url, expires) }
            }
            trimTotal(applicationContext)
            return Result.success()
        }
    }

    private fun preload(ctx: Context, gameId: String, pageUrl: String, expiresAt: Long) {
        if (!pageUrl.startsWith("https://", true) && !pageUrl.startsWith("http://", true)) return
        val ua = runCatching { android.webkit.WebSettings.getDefaultUserAgent(ctx) }.getOrNull()
        val baseHeaders = buildMap { ua?.let { put("User-Agent", it) }; put("Accept-Language", Locale.getDefault().toLanguageTag()) }
        val page = SafeHttp.get(pageUrl, baseHeaders + ("Accept" to "text/html,*/*;q=0.8"), maxBytes = 3 * 1024 * 1024, deadlineMs = 15_000) ?: return
        if (page.code !in 200..299) return
        val html = String(page.body, Charsets.UTF_8)
        val pageOrigin = originOf(page.finalUrl)
        AdBlocker.warmUp(ctx)
        val engine = AdBlocker.network()
        val pageHost = runCatching { URL(page.finalUrl).host.lowercase(Locale.US) }.getOrNull()
        val queue = ArrayDeque(subresources(html, page.finalUrl))
        val dir = gameDir(ctx, gameId).apply { deleteRecursively(); mkdirs() }
        val index = JSONObject()
        var total = 0L
        var n = 0
        val seen = HashSet<String>()
        while (queue.isNotEmpty() && n < MAX_FILES && total < MAX_GAME_BYTES) {
            val res = queue.removeFirst()
            if (!seen.add(res)) continue
            val type = AdBlockEngine.inferType(res, null, false, null)
            if (engine != null && engine.shouldBlock(res, pageHost, type)) continue
            val crossOrigin = originOf(res) != pageOrigin
            val headers = baseHeaders + (if (crossOrigin && pageOrigin != null) mapOf("Origin" to pageOrigin, "Referer" to page.finalUrl) else mapOf("Referer" to page.finalUrl))
            val r = SafeHttp.get(res, headers, maxBytes = MAX_FILE_BYTES, deadlineMs = 12_000) ?: continue
            if (r.code != 200 || r.headers["x-dailies-truncated"] != null) continue
            val cc = r.headers["cache-control"].orEmpty().lowercase(Locale.US)
            if ("no-store" in cc || "private" in cc) continue
            val (mime, enc) = splitType(r.contentType, res)
            val file = File(dir, sha(res).take(32))
            file.writeBytes(r.body)
            total += r.body.size
            n++
            val keep = JSONObject()
            listOf("access-control-allow-origin", "access-control-allow-credentials", "timing-allow-origin", "cross-origin-resource-policy")
                .forEach { h -> r.headers[h]?.let { keep.put(h, it) } }
            index.put(res, JSONObject().put("f", file.name).put("m", mime).put("e", enc ?: "").put("h", keep))
            // Fonts a stylesheet uses are needed straight away too.
            if (mime == "text/css") cssFonts(String(r.body, Charsets.UTF_8), res).forEach { queue.addLast(it) }
        }
        if (n == 0) { dir.deleteRecursively(); return }
        File(dir, "index.json").writeText(JSONObject().put("game", gameId).put("expires", expiresAt).put("files", index).toString())
    }

    /** Drops the oldest games' files while everything together is over the limit. */
    private fun trimTotal(ctx: Context) {
        val dirs = root(ctx).listFiles()?.filter { it.isDirectory }?.sortedBy { it.lastModified() } ?: return
        var total = dirs.sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L }
        for (d in dirs) {
            if (total <= MAX_TOTAL_BYTES) break
            total -= d.listFiles()?.sumOf { it.length() } ?: 0L
            d.deleteRecursively()
        }
    }

    // ---- Serving -----------------------------------------------------------------------------

    /** A game's preloaded files, or null when there are none (or they've expired). */
    fun load(ctx: Context, gameId: String, now: Long = System.currentTimeMillis()): Index? = runCatching {
        val dir = gameDir(ctx, gameId)
        val f = File(dir, "index.json")
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val expires = o.optLong("expires")
        if (o.optString("game") != gameId || now >= expires) { dir.deleteRecursively(); return null }
        val files = o.optJSONObject("files") ?: return null
        val map = HashMap<String, Entry>()
        files.keys().forEach { url ->
            val e = files.getJSONObject(url)
            val h = e.optJSONObject("h")
            val headers = HashMap<String, String>()
            h?.keys()?.forEach { k -> headers[k] = h.getString(k) }
            map[url] = Entry(File(dir, e.getString("f")), e.getString("m"), e.optString("e").ifEmpty { null }, headers)
        }
        Index(gameId, expires, map)
    }.getOrNull()

    /** The preloaded copy of a sub-resource request, or null to load it from the network. */
    fun respond(index: Index?, request: WebResourceRequest): WebResourceResponse? {
        if (index == null || request.isForMainFrame || !request.method.equals("GET", true)) return null
        if (System.currentTimeMillis() >= index.expiresAt) return null
        val entry = index.entries[request.url.toString()] ?: return null
        // A cross-origin (CORS) request needs the server's permission header; without it, use the network.
        val hasOrigin = request.requestHeaders.orEmpty().keys.any { it.equals("Origin", true) }
        if (hasOrigin && entry.headers["access-control-allow-origin"] == null) return null
        if (!entry.file.exists()) return null
        val headers = HashMap(entry.headers).apply { put("Cache-Control", "no-cache") }
        return runCatching { WebResourceResponse(entry.mime, entry.encoding, 200, "OK", headers, FileInputStream(entry.file)) }.getOrNull()
    }

    // ---- Parsing (pure; unit tested) ---------------------------------------------------------

    private val SCRIPT_RE = Regex("""<script\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val LINK_RE = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val HREF_RE = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val REL_RE = Regex("""\brel\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val AS_RE = Regex("""\bas\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val BASE_RE = Regex("""<base\b[^>]*\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val CSS_URL_RE = Regex("""url\(\s*["']?([^"')]+\.(?:woff2?|ttf|otf)(?:\?[^"')]*)?)["']?\s*\)""", RegexOption.IGNORE_CASE)

    /** Scripts, stylesheets and preloaded files a page asks for, as absolute http(s) URLs, in page order. */
    fun subresources(html: String, pageUrl: String): List<String> {
        val base = BASE_RE.find(html)?.groupValues?.get(1)?.let { resolve(pageUrl, it) } ?: pageUrl
        val found = ArrayList<Pair<Int, String>>()
        SCRIPT_RE.findAll(html).forEach { m -> resolve(base, m.groupValues[1])?.let { found += m.range.first to it } }
        LINK_RE.findAll(html).forEach { m ->
            val tag = m.value
            val rel = REL_RE.find(tag)?.groupValues?.get(1)?.lowercase(Locale.US)?.split(Regex("\\s+")).orEmpty()
            val asType = AS_RE.find(tag)?.groupValues?.get(1)?.lowercase(Locale.US)
            val wanted = "stylesheet" in rel || "modulepreload" in rel ||
                ("preload" in rel && asType in setOf("script", "style", "font"))
            if (!wanted) return@forEach
            HREF_RE.find(tag)?.groupValues?.get(1)?.let { href -> resolve(base, href)?.let { found += m.range.first to it } }
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    /** Font files a stylesheet loads. */
    fun cssFonts(css: String, cssUrl: String): List<String> =
        CSS_URL_RE.findAll(css).mapNotNull { resolve(cssUrl, it.groupValues[1]) }.distinct().take(8).toList()

    private fun resolve(base: String, ref: String): String? {
        val r = ref.trim().replace("&amp;", "&")
        if (r.startsWith("data:", true) || r.startsWith("blob:", true) || r.startsWith("javascript:", true)) return null
        val u = runCatching { URL(URL(base), r) }.getOrNull() ?: return null
        val scheme = u.protocol.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return null
        return u.toString().substringBefore('#')
    }

    private fun originOf(url: String): String? = runCatching {
        val u = URL(url)
        "${u.protocol.lowercase(Locale.US)}://${u.host.lowercase(Locale.US)}" + (if (u.port != -1 && u.port != u.defaultPort) ":${u.port}" else "")
    }.getOrNull()

    /** MIME type and charset from a Content-Type header (or the file extension when it's missing). */
    fun splitType(contentType: String?, url: String): Pair<String, String?> {
        val ct = contentType?.trim().orEmpty()
        val mime = ct.substringBefore(';').trim().lowercase(Locale.US).ifEmpty {
            when (url.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.US)) {
                "js", "mjs" -> "text/javascript"
                "css" -> "text/css"
                "woff2" -> "font/woff2"
                "woff" -> "font/woff"
                "ttf" -> "font/ttf"
                else -> "application/octet-stream"
            }
        }
        val charset = Regex("""charset\s*=\s*["']?([^;"'\s]+)""", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
        return mime to (charset ?: if (mime.startsWith("text/") || mime.contains("javascript")) "utf-8" else null)
    }
}
