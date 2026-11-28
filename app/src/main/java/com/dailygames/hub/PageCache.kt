package com.dailygames.hub

import android.content.Context
import android.net.ConnectivityManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Preloaded games (1.6.2, upgraded for parallel module/manifest graph warming).
 *
 * When a game's new puzzle comes out — or when you open the hub on Wi-Fi with unplayed Ready games —
 * the files its page needs (scripts, ES module chunks, Next.js route chunks, stylesheets, fonts, and
 * preloaded data) are downloaded in parallel on Wi-Fi so opening the game later is fast. The main HTML
 * document always comes from the site (logins and today's puzzle stay fresh); subresources are served
 * from here when Chromium hasn't natively cached them yet, and only after the ad blocker lets them through.
 */
object PageCache {
    private const val DIR = "pagecache"
    private const val MAX_FILES = 64
    private const val MAX_FILE_BYTES = 4 * 1024 * 1024
    private const val MAX_GAME_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 80L * 1024 * 1024
    private const val MAX_JS_SCAN_BYTES = 512 * 1024
    private const val FETCH_CONCURRENCY = 4
    private const val GAME_CONCURRENCY = 2
    private const val ATTEMPT_COOLDOWN_MS = 10 * 60_000L
    private const val WORK_NAME = "page_cache_preload"
    private const val PREFS = "dailes_game_prefs"
    private const val KEY_ENABLED = "preload_games"
    private const val KEY_PENDING_IDS = "preload_pending_ids"

    private val pendingLock = Any()
    private val recentAttempts = ConcurrentHashMap<String, Long>()
    private val gameLocks = ConcurrentHashMap<String, Any>()

    class Entry(
        val file: File,
        val mime: String,
        val encoding: String?,
        val headers: Map<String, String>,
        val isModule: Boolean = false
    )

    class Index(
        val gameId: String,
        val expiresAt: Long,
        val entries: Map<String, Entry>,
        val finalUrl: String? = null,
        val origins: List<String> = emptyList()
    )

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ENABLED, on)
            if (!on) remove(KEY_PENDING_IDS)
        }
        recentAttempts.clear()
        if (!on) {
            runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME) }
            clearAll(ctx)
        }
    }

    private fun root(ctx: Context) = File(ctx.cacheDir, DIR)
    private fun gameDir(ctx: Context, gameId: String) = File(root(ctx), sha(gameId).take(24))
    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Deletes one game's files (it was logged, removed, or its puzzle changed). */
    fun clear(ctx: Context, gameId: String) {
        recentAttempts.remove(gameId)
        runCatching { gameDir(ctx, gameId).deleteRecursively() }
    }

    fun clearAll(ctx: Context) {
        recentAttempts.clear()
        synchronized(pendingLock) {
            runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_PENDING_IDS) } }
        }
        runCatching { root(ctx).deleteRecursively() }
    }

    fun hasValidCache(ctx: Context, gameId: String, nowMs: Long = System.currentTimeMillis()): Boolean = runCatching {
        val dir = gameDir(ctx, gameId)
        val f = File(dir, "index.json")
        if (!f.exists()) return false
        val o = JSONObject(f.readText())
        val expires = o.optLong("expires")
        if (o.optString("game") != gameId || nowMs >= expires) {
            dir.deleteRecursively()
            return false
        }
        (o.optJSONObject("files")?.length() ?: 0) > 0
    }.getOrDefault(false)

    private fun addPendingIds(ctx: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(pendingLock) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val merged = HashSet(prefs.getStringSet(KEY_PENDING_IDS, emptySet()).orEmpty())
            merged.addAll(ids.filter { it.isNotBlank() })
            prefs.edit { putStringSet(KEY_PENDING_IDS, merged) }
        }
    }

    private fun drainPendingIds(ctx: Context): Set<String> = synchronized(pendingLock) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_PENDING_IDS, emptySet()).orEmpty().toSet()
        if (current.isNotEmpty()) prefs.edit { remove(KEY_PENDING_IDS) }
        current
    }

    // ---- Downloading -----------------------------------------------------------------------

    /** Queues preloading for games whose puzzle just reset (runs on an unmetered network). */
    fun enqueue(ctx: Context, gameIds: Collection<String>) {
        val clean = gameIds.filter { it.isNotBlank() }
        if (clean.isEmpty() || !enabled(ctx)) return
        // The old puzzle's files go now, whether or not the new ones can be fetched.
        clean.forEach { clear(ctx, it) }
        addPendingIds(ctx, clean)
        scheduleWorker(ctx, clean)
    }

    /** Queues preloading for Ready games that don't have a valid cache yet without wiping cached ones. */
    fun enqueueMissing(ctx: Context, gameIds: Collection<String>) {
        val clean = gameIds.filter { it.isNotBlank() }
        if (clean.isEmpty() || !enabled(ctx)) return
        val now = System.currentTimeMillis()
        clean.forEach { recentAttempts[it] = now }
        addPendingIds(ctx, clean)
        scheduleWorker(ctx, clean)
    }

    /** Checks tracked Ready games and queues any that aren't cached for their current cycle yet. */
    fun ensureReadyGamesCached(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        if (!enabled(ctx)) return
        val games = GameStore.load(ctx)
        if (games.length() == 0) return
        val missing = uncachedReadyIds(
            games = games,
            isCached = { id -> hasValidCache(ctx, id, nowMs) },
            recentlyAttempted = { id ->
                val last = recentAttempts[id] ?: 0L
                last > 0L && nowMs - last < ATTEMPT_COOLDOWN_MS
            },
            nowMs = nowMs
        )
        if (missing.isNotEmpty()) enqueueMissing(ctx, missing)
    }

    private fun scheduleWorker(ctx: Context, ids: Collection<String>) {
        val req = OneTimeWorkRequestBuilder<PreloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setInputData(workDataOf("ids" to ids.toTypedArray()))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    class PreloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            if (!enabled(applicationContext)) return@withContext Result.success()
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm?.isActiveNetworkMetered == true) return@withContext Result.success()

            var batch = LinkedHashSet<String>().apply {
                inputData.getStringArray("ids")?.forEach { if (it.isNotBlank()) add(it) }
                addAll(drainPendingIds(applicationContext))
            }
            while (batch.isNotEmpty() && enabled(applicationContext)) {
                val games = GameStore.load(applicationContext)
                val now = System.currentTimeMillis()
                val targets = ArrayList<Triple<String, String, Long>>()
                for (i in 0 until games.length()) {
                    val g = games.optJSONObject(i) ?: continue
                    val id = g.optString("id")
                    if (id !in batch || !GameStore.hasPreloadableUrl(g)) continue
                    val url = g.optString("url")
                    val expires = now + GameStore.msUntilNextReset(
                        g.optString("resetTime", "00:00"),
                        g.optString("resetZone", "local"),
                        now
                    )
                    targets += Triple(id, url, expires)
                }
                val gameSem = Semaphore(GAME_CONCURRENCY)
                coroutineScope {
                    targets.map { (id, url, expires) ->
                        async {
                            gameSem.withPermit {
                                if (enabled(applicationContext)) {
                                    recentAttempts[id] = System.currentTimeMillis()
                                    runCatching { preloadGame(applicationContext, id, url, expires) }
                                }
                            }
                        }
                    }.awaitAll()
                }
                batch = LinkedHashSet(drainPendingIds(applicationContext))
            }
            trimTotal(applicationContext)
            Result.success()
        }
    }

    private class FetchedAsset(
        val url: String,
        val body: ByteArray,
        val mime: String,
        val enc: String?,
        val headers: JSONObject,
        val isModule: Boolean,
        val discoveredUrls: List<String>,
        val discoveredModules: Set<String>
    )

    /**
     * Downloads and atomically stages subresources for [gameId], returning the loaded [Index] (or
     * the already-valid cached [Index] if another caller just populated it).
     */
    suspend fun preloadGame(ctx: Context, gameId: String, pageUrl: String, expiresAt: Long): Index? =
        withContext(Dispatchers.IO) {
            if (!enabled(ctx)) return@withContext null
            if (!pageUrl.startsWith("https://", true) && !pageUrl.startsWith("http://", true)) return@withContext null
            load(ctx, gameId)?.let { return@withContext it }

            val ua = runCatching { android.webkit.WebSettings.getDefaultUserAgent(ctx)?.replace("; wv", "") }.getOrNull()
            val baseHeaders = buildMap {
                ua?.let { put("User-Agent", it) }
                put("Accept-Language", Locale.getDefault().toLanguageTag())
            }
            val page = SafeHttp.get(
                pageUrl,
                baseHeaders + ("Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"),
                maxBytes = 3 * 1024 * 1024,
                deadlineMs = 15_000
            ) ?: return@withContext null
            if (page.code !in 200..299) return@withContext null

            val html = String(page.body, Charsets.UTF_8)
            val pageOrigin = originOf(page.finalUrl)
            AdBlocker.warmUp(ctx)
            val engine = AdBlocker.network()
            val pageHost = runCatching { URL(page.finalUrl).host.lowercase(Locale.US) }.getOrNull()

            val initialSubs = subresources(html, page.finalUrl)
            val moduleSet = HashSet(moduleResources(html, page.finalUrl))
            val origins = preconnectOrigins(html, pageUrl, page.finalUrl)
            val finalDir = gameDir(ctx, gameId)
            val stage = File(root(ctx), "${finalDir.name}.${System.nanoTime().and(0xFFFFFFL)}.tmp").apply {
                deleteRecursively()
                mkdirs()
            }

            val filesJson = JSONObject()
            var total = 0L
            var n = 0
            val seen = HashSet<String>()
            var wave = ArrayList(initialSubs)
            val fetchSem = Semaphore(FETCH_CONCURRENCY)

            try {
                while (wave.isNotEmpty() && n < MAX_FILES && total < MAX_GAME_BYTES) {
                    if (!enabled(ctx)) {
                        stage.deleteRecursively()
                        return@withContext null
                    }
                    val candidates = ArrayList<String>()
                    for (res in wave) {
                        if (candidates.size >= MAX_FILES - n) break
                        if (!seen.add(res)) continue
                        val type = AdBlockEngine.inferType(res, null, false, null)
                        if (engine != null && engine.shouldBlock(res, pageHost, type)) continue
                        candidates += res
                    }
                    if (candidates.isEmpty()) break

                    val fetched = coroutineScope {
                        candidates.map { res ->
                            val isMod = res in moduleSet
                            async {
                                fetchSem.withPermit {
                                    if (!enabled(ctx)) return@withPermit null
                                    val crossOrigin = originOf(res) != pageOrigin
                                    val reqHeaders = baseHeaders + buildMap {
                                        put("Referer", page.finalUrl)
                                        if ((crossOrigin || isMod) && pageOrigin != null) put("Origin", pageOrigin)
                                    }
                                    val r = SafeHttp.get(res, reqHeaders, maxBytes = MAX_FILE_BYTES + 1, deadlineMs = 12_000)
                                        ?: return@withPermit null
                                    if (r.code != 200 || r.body.size > MAX_FILE_BYTES || r.headers["x-dailies-truncated"] != null) {
                                        return@withPermit null
                                    }
                                    val cc = r.headers["cache-control"].orEmpty().lowercase(Locale.US)
                                    if ("no-store" in cc || "private" in cc) return@withPermit null
                                    val (mime, enc) = splitType(r.contentType, res)
                                    val keep = JSONObject()
                                    SAVED_HEADERS.forEach { h -> r.headers[h]?.let { keep.put(h, it) } }

                                    val extraUrls = ArrayList<String>()
                                    val extraMods = HashSet<String>()
                                    if (mime == "text/css" && r.body.size <= MAX_JS_SCAN_BYTES) {
                                        cssFonts(String(r.body, Charsets.UTF_8), res).forEach { extraUrls += it }
                                    } else if (mime.contains("javascript") && r.body.size <= MAX_JS_SCAN_BYTES) {
                                        val imports = esModuleImports(String(r.body, Charsets.UTF_8), res)
                                        imports.forEach { u ->
                                            extraUrls += u
                                            if (isMod || u.substringBefore('?').endsWith(".mjs", true)) extraMods += u
                                        }
                                    }
                                    FetchedAsset(res, r.body, mime, enc, keep, isMod, extraUrls, extraMods)
                                }
                            }
                        }.awaitAll()
                    }

                    val nextWave = ArrayList<String>()
                    for (item in fetched) {
                        if (item == null) continue
                        if (n >= MAX_FILES || total + item.body.size > MAX_GAME_BYTES) break
                        val file = File(stage, sha(item.url).take(32))
                        file.writeBytes(item.body)
                        total += item.body.size
                        n++
                        filesJson.put(
                            item.url,
                            JSONObject()
                                .put("f", file.name)
                                .put("m", item.mime)
                                .put("e", item.enc ?: "")
                                .put("k", if (item.isModule) "module" else "")
                                .put("h", item.headers)
                        )
                        moduleSet.addAll(item.discoveredModules)
                        item.discoveredUrls.forEach { u -> if (u !in seen) nextWave += u }
                    }
                    wave = nextWave
                }

                if (n == 0 || !enabled(ctx)) {
                    stage.deleteRecursively()
                    return@withContext null
                }

                val originsArr = JSONArray().apply { origins.forEach { put(it) } }
                val indexJson = JSONObject()
                    .put("game", gameId)
                    .put("expires", expiresAt)
                    .put("finalUrl", page.finalUrl)
                    .put("origins", originsArr)
                    .put("files", filesJson)
                    .toString()

                val lock = gameLocks.computeIfAbsent(gameId) { Any() }
                synchronized(lock) {
                    val indexTmp = File(stage, "index.json.tmp")
                    val indexFile = File(stage, "index.json")
                    indexTmp.writeText(indexJson)
                    if (!indexTmp.renameTo(indexFile)) {
                        indexFile.writeText(indexJson)
                        indexTmp.delete()
                    }
                    if (!enabled(ctx)) {
                        stage.deleteRecursively()
                        return@withContext null
                    }
                    val oldDir = File(root(ctx), "${finalDir.name}.old").apply { deleteRecursively() }
                    val hadExisting = finalDir.exists()
                    if (hadExisting && !finalDir.renameTo(oldDir)) {
                        finalDir.deleteRecursively()
                    }
                    if (!enabled(ctx)) {
                        stage.deleteRecursively()
                        oldDir.deleteRecursively()
                        return@withContext null
                    }
                    if (!stage.renameTo(finalDir)) {
                        stage.deleteRecursively()
                        if (oldDir.exists()) oldDir.renameTo(finalDir)
                    } else {
                        oldDir.deleteRecursively()
                    }
                }
                load(ctx, gameId)
            } catch (t: Throwable) {
                runCatching { stage.deleteRecursively() }
                throw t
            }
        }

    /** Drops stale .tmp/.old dirs (>1h) and the oldest games' files while over the total limit. */
    fun trimTotal(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        val entries = root(ctx).listFiles() ?: return
        val staleCutoff = nowMs - 3_600_000L
        entries.filter { it.isDirectory && (it.name.endsWith(".tmp") || it.name.endsWith(".old")) && it.lastModified() < staleCutoff }
            .forEach { it.deleteRecursively() }
        val dirs = entries.filter { it.isDirectory && !it.name.endsWith(".tmp") && !it.name.endsWith(".old") }.sortedBy { it.lastModified() }
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
        val map = LinkedHashMap<String, Entry>()
        files.keys().forEach { url ->
            val e = files.getJSONObject(url)
            val h = e.optJSONObject("h")
            val headers = HashMap<String, String>()
            h?.keys()?.forEach { k -> headers[k] = h.getString(k) }
            val isModule = e.optString("k") == "module"
            map[url] = Entry(File(dir, e.getString("f")), e.getString("m"), e.optString("e").ifEmpty { null }, headers, isModule)
        }
        val origins = ArrayList<String>()
        o.optJSONArray("origins")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i)?.takeIf { it.isNotBlank() }?.let { origins += it }
            }
        }
        val finalUrl = o.optString("finalUrl").ifEmpty { null }
        Index(gameId, expires, map, finalUrl, origins)
    }.getOrNull()

    /** Checks whether a cached entry's CORS header permits [requestHeaders] (and same-origin [resourceUrl]). */
    fun corsAllows(entry: Entry, requestHeaders: Map<String, String>?, resourceUrl: String? = null): Boolean {
        val reqOrigin = requestHeaders.orEmpty().entries.firstOrNull { it.key.equals("Origin", true) }?.value?.trim()
            ?: return true
        // Same-origin ES module (<script type="module">) and fetch() requests send an Origin header in
        // Chromium WebView, even when the server omitted Access-Control-Allow-Origin on a same-origin file.
        if (resourceUrl != null) {
            val resOrigin = originOf(resourceUrl)
            if (resOrigin != null && resOrigin.equals(reqOrigin, ignoreCase = true)) return true
        }
        val acao = entry.headers["access-control-allow-origin"]?.trim() ?: return false
        return acao == "*" || acao.equals(reqOrigin, ignoreCase = true)
    }

    /** Whether Chromium's native HTTP disk cache can serve [entry] without network revalidation. */
    fun isBrowserCacheable(entry: Entry): Boolean {
        val lc = entry.headers["cache-control"]?.lowercase(Locale.US).orEmpty()
        if ("no-cache" in lc || "no-store" in lc || "must-revalidate" in lc) return false
        val maxAge = MAX_AGE_RE.find(lc)?.groupValues?.get(1)?.toLongOrNull()
        if (maxAge != null) return maxAge > 0L
        return "immutable" in lc
    }

    /** Builds response headers for a cached entry, preserving immutable/long max-age headers instead of forcing no-cache. */
    fun responseHeaders(entry: Entry, expiresAt: Long, nowMs: Long = System.currentTimeMillis()): Map<String, String> {
        val out = HashMap<String, String>()
        entry.headers.forEach { (k, v) ->
            if (!k.equals("cache-control", ignoreCase = true)) out[k] = v
        }
        val upstreamCc = entry.headers["cache-control"]?.trim().orEmpty()
        val lc = upstreamCc.lowercase(Locale.US)
        val maxAge = MAX_AGE_RE.find(lc)?.groupValues?.get(1)?.toLongOrNull()
        if ("immutable" in lc || (maxAge != null && maxAge >= 3_600L && "no-cache" !in lc && "must-revalidate" !in lc)) {
            out["Cache-Control"] = upstreamCc
        } else {
            val remainingSec = ((expiresAt - nowMs) / 1_000L).coerceIn(60L, 86_400L)
            out["Cache-Control"] = "public, max-age=$remainingSec"
        }
        return out
    }

    /**
     * The preloaded copy of a sub-resource request, or null to let Chromium serve it from its native
     * HTTP/V8 bytecode cache (when [nativelyWarmed] is true for browser-cacheable assets) or the network.
     */
    fun respond(index: Index?, request: WebResourceRequest, nativelyWarmed: Boolean = false): WebResourceResponse? {
        if (index == null || request.isForMainFrame || !request.method.equals("GET", true)) return null
        val now = System.currentTimeMillis()
        if (now >= index.expiresAt) return null
        val urlStr = request.url.toString()
        val entry = index.entries[urlStr] ?: return null
        if (nativelyWarmed && isBrowserCacheable(entry)) return null
        if (!corsAllows(entry, request.requestHeaders, urlStr)) return null
        if (!entry.file.exists()) return null
        val headers = responseHeaders(entry, index.expiresAt, now)
        return runCatching { WebResourceResponse(entry.mime, entry.encoding, 200, "OK", headers, FileInputStream(entry.file)) }.getOrNull()
    }

    // ---- Parsing & Eligibility (pure; unit tested) -------------------------------------------

    private val SAVED_HEADERS = listOf(
        "access-control-allow-origin",
        "access-control-allow-credentials",
        "timing-allow-origin",
        "cross-origin-resource-policy",
        "cache-control",
        "etag"
    )
    private val MAX_AGE_RE = Regex("""\bmax-age\s*=\s*(\d+)""")
    private val SCRIPT_TAG_RE = Regex("""<script\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val SRC_RE = Regex("""\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val TYPE_RE = Regex("""\btype\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val LINK_RE = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val HREF_RE = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val REL_RE = Regex("""\brel\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val AS_RE = Regex("""\bas\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val BASE_RE = Regex("""<base\b[^>]*\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val CSS_URL_RE = Regex("""url\(\s*["']?([^"')]+\.(?:woff2?|ttf|otf)(?:\?[^"')]*)?)["']?\s*\)""", RegexOption.IGNORE_CASE)
    private val ES_STATIC_IMPORT_RE = Regex(
        """(?:^|[;\n\r{}])\s*\b(?:import|export)\b(?!\s*\()(?:[^"';\n\r]{0,240}?\bfrom\s*)?\s*["']((?:\.\.?/|/)[^"'\s<>]+\.m?js(?:\?[^"'\s<>]*)?)["']"""
    )
    private val ES_DYNAMIC_IMPORT_RE = Regex(
        """\bimport\s*\(\s*["']((?:\.\.?/|/)[^"'\s<>]+\.m?js(?:\?[^"'\s<>]*)?)["']\s*\)"""
    )
    private val NEXT_MANIFEST_CHUNK_RE = Regex("""["'](static/chunks/[^"'\s<>]+\.js)["']""")

    /** Scripts, stylesheets and preloaded files a page asks for, as absolute http(s) URLs, in page order. */
    fun subresources(html: String, pageUrl: String): List<String> {
        val base = BASE_RE.find(html)?.groupValues?.get(1)?.let { resolve(pageUrl, it) } ?: pageUrl
        val found = ArrayList<Pair<Int, String>>()
        SCRIPT_TAG_RE.findAll(html).forEach { m ->
            val src = SRC_RE.find(m.value)?.groupValues?.get(1) ?: return@forEach
            resolve(base, src)?.let { found += m.range.first to it }
        }
        LINK_RE.findAll(html).forEach { m ->
            val tag = m.value
            val rel = REL_RE.find(tag)?.groupValues?.get(1)?.lowercase(Locale.US)?.split(Regex("\\s+")).orEmpty()
            val asType = AS_RE.find(tag)?.groupValues?.get(1)?.lowercase(Locale.US)
            val wanted = "stylesheet" in rel || "modulepreload" in rel ||
                (("preload" in rel || "prefetch" in rel) && asType in setOf("script", "style", "font", "fetch"))
            if (!wanted) return@forEach
            HREF_RE.find(tag)?.groupValues?.get(1)?.let { href -> resolve(base, href)?.let { found += m.range.first to it } }
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    /** Subset of [subresources] declared as ES modules (`<script type="module">` or `<link rel="modulepreload">`). */
    fun moduleResources(html: String, pageUrl: String): Set<String> {
        val base = BASE_RE.find(html)?.groupValues?.get(1)?.let { resolve(pageUrl, it) } ?: pageUrl
        val out = LinkedHashSet<String>()
        SCRIPT_TAG_RE.findAll(html).forEach { m ->
            val tag = m.value
            val type = TYPE_RE.find(tag)?.groupValues?.get(1)?.trim()?.lowercase(Locale.US)
            if (type != "module") return@forEach
            val src = SRC_RE.find(tag)?.groupValues?.get(1) ?: return@forEach
            resolve(base, src)?.let { out += it }
        }
        LINK_RE.findAll(html).forEach { m ->
            val tag = m.value
            val rel = REL_RE.find(tag)?.groupValues?.get(1)?.lowercase(Locale.US)?.split(Regex("\\s+")).orEmpty()
            if ("modulepreload" !in rel) return@forEach
            HREF_RE.find(tag)?.groupValues?.get(1)?.let { href -> resolve(base, href)?.let { out += it } }
        }
        return out
    }

    /** Static and dynamic ES module chunk imports (plus Next.js `_buildManifest` chunks) referenced by a JS file. */
    fun esModuleImports(js: String, scriptUrl: String): List<String> {
        val scriptOrigin = originOf(scriptUrl) ?: return emptyList()
        val found = LinkedHashSet<String>()
        ES_STATIC_IMPORT_RE.findAll(js).forEach { m ->
            resolve(scriptUrl, m.groupValues[1])?.takeIf { originOf(it) == scriptOrigin }?.let { found += it }
        }
        ES_DYNAMIC_IMPORT_RE.findAll(js).forEach { m ->
            resolve(scriptUrl, m.groupValues[1])?.takeIf { originOf(it) == scriptOrigin }?.let { found += it }
        }
        if (scriptUrl.contains("/_next/") && scriptUrl.contains("Manifest.js")) {
            val nextRoot = scriptUrl.substringBefore("/_next/") + "/_next/"
            NEXT_MANIFEST_CHUNK_RE.findAll(js).forEach { m ->
                resolve(nextRoot, m.groupValues[1])?.takeIf { originOf(it) == scriptOrigin }?.let { found += it }
            }
        }
        return found.take(20).toList()
    }

    /** Up to 6 http(s) origins (page + preconnect hints + CDN subresource hosts) worth preconnecting to. */
    fun preconnectOrigins(html: String, pageUrl: String, finalUrl: String = pageUrl): List<String> {
        val base = BASE_RE.find(html)?.groupValues?.get(1)?.let { resolve(finalUrl, it) } ?: finalUrl
        val out = LinkedHashSet<String>()
        originOf(finalUrl)?.let { out += it }
        originOf(pageUrl)?.let { out += it }
        LINK_RE.findAll(html).forEach { m ->
            val tag = m.value
            val rel = REL_RE.find(tag)?.groupValues?.get(1)?.lowercase(Locale.US)?.split(Regex("\\s+")).orEmpty()
            if ("preconnect" !in rel && "dns-prefetch" !in rel) return@forEach
            val href = HREF_RE.find(tag)?.groupValues?.get(1) ?: return@forEach
            resolve(base, href)?.let { originOf(it) }?.let { out += it }
        }
        subresources(html, finalUrl).forEach { u ->
            if (out.size >= 6) return@forEach
            originOf(u)?.let { out += it }
        }
        return out.take(6).toList()
    }

    /** Active, unplayed Ready game IDs that don't yet have a valid cache for their current cycle. */
    fun uncachedReadyIds(
        games: JSONArray,
        isCached: (String) -> Boolean,
        recentlyAttempted: (String) -> Boolean = { false },
        nowMs: Long = System.currentTimeMillis(),
        localTimeZone: TimeZone = TimeZone.getDefault()
    ): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until games.length()) {
            val g = games.optJSONObject(i) ?: continue
            val id = g.optString("id")
            if (id.isEmpty() || !GameStore.hasPreloadableUrl(g)) continue
            val cycle = GameStore.cycleKey(
                g.optString("resetTime", "00:00"),
                g.optString("resetZone", "local"),
                nowMs,
                localTimeZone
            )
            val doneForCycle = g.optString("loggedCycle") == cycle ||
                g.optString("lastPlayedCycle") == cycle ||
                ((g.optString("status") == "completed" || g.optString("status") == "missed") && g.optString("lastCycleKey") == cycle)
            if (doneForCycle) continue
            if (isCached(id) || recentlyAttempted(id)) continue
            out += id
        }
        return out
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

    fun originOf(url: String): String? = runCatching {
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
                "json", "geojson" -> "application/json"
                "woff2" -> "font/woff2"
                "woff" -> "font/woff"
                "ttf" -> "font/ttf"
                else -> "application/octet-stream"
            }
        }
        val charset = Regex("""charset\s*=\s*["']?([^;"'\s]+)""", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
        return mime to (charset ?: if (mime.startsWith("text/") || mime.contains("javascript") || mime == "application/json") "utf-8" else null)
    }
}
