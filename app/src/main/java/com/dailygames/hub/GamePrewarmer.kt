package com.dailygames.hub

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Warms Chromium's native partitioned HTTP disk cache, ES module graphs, and TLS/HTTP2 sockets for
 * unplayed Ready games while the user is browsing the Hub on an unmetered network.
 *
 * Safety & side-effect guarantees:
 * - Uses `loadDataWithBaseURL(gameUrl, warmerHtml, ...)` with `javaScriptEnabled = false` and
 *   `domStorageEnabled = false`, so Chromium populates the game origin's exact cache partition and
 *   recursively preloads `<link rel="modulepreload">` / `<link rel="preload">` / `<link rel="stylesheet">`
 *   resources over HTTP/2 + Brotli **without** executing any game JavaScript, starting in-game timers,
 *   writing to `localStorage`/`IndexedDB`, or playing audio.
 * - Keeps its `WebView` completely detached from the Activity view hierarchy and destroys it as soon as
 *   the pass finishes or the user leaves the Hub, keeping steady-state RAM overhead at zero.
 */
class GamePrewarmer(private val host: ViewGroup) {
    private val appCtx: Context = host.context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJob: Job? = null
    private var webView: WebView? = null
    private var paused = false
    private val scheduleRunnable = Runnable { startWarmupIfEligible() }
    private var lastPreconnectAtElapsed = 0L

    private class WarmTarget(
        val id: String,
        val url: String,
        val cycleKey: String,
        val expiresAt: Long
    )

    fun scheduleWarmup(delayMs: Long = 1500L) {
        if (paused) return
        main.removeCallbacks(scheduleRunnable)
        main.postDelayed(scheduleRunnable, delayMs.coerceAtLeast(0L))
    }

    fun onHubResumed() {
        paused = false
        scheduleWarmup(delayMs = 2500L)
    }

    /** Halts any background warming immediately (e.g. when tapping Play or leaving the Hub). */
    fun pauseAndStop() {
        paused = true
        main.removeCallbacks(scheduleRunnable)
        activeJob?.cancel()
        activeJob = null
        destroyWebView()
    }

    fun clearAndStop() {
        clearWarmedState()
        pauseAndStop()
    }

    fun destroy() {
        pauseAndStop()
        scope.cancel()
    }

    private fun isUnmeteredNetwork(): Boolean {
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        if (cm.isActiveNetworkMetered) return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun startWarmupIfEligible() {
        if (paused || !PageCache.enabled(appCtx) || !isUnmeteredNetwork()) return
        if (activeJob?.isActive == true) return

        activeJob = scope.launch {
            try {
                val now = System.currentTimeMillis()
                val games = kotlinx.coroutines.withContext(Dispatchers.IO) { GameStore.load(appCtx) }
                val ready = collectReadyTargets(games, now)
                if (ready.isEmpty() || paused) return@launch

                val unwarmed = ready.filter { t -> warmedCycles[t.id] != t.cycleKey }.take(MAX_GAMES_PER_PASS)
                if (unwarmed.isNotEmpty()) {
                    for (target in unwarmed) {
                        if (paused || !PageCache.enabled(appCtx) || !isUnmeteredNetwork()) break
                        warmSingleGame(target)
                    }
                    lastPreconnectAtElapsed = SystemClock.elapsedRealtime()
                } else {
                    val elapsed = SystemClock.elapsedRealtime()
                    if (lastPreconnectAtElapsed == 0L || elapsed - lastPreconnectAtElapsed >= PRECONNECT_COOLDOWN_MS) {
                        val origins = LinkedHashSet<String>()
                        ready.take(MAX_PRECONNECT_ORIGINS).forEach { t ->
                            PageCache.originOf(t.url)?.let { origins += it }
                        }
                        if (origins.isNotEmpty() && !paused) {
                            lastPreconnectAtElapsed = elapsed
                            val base = origins.first() + "/"
                            loadHtmlAndAwait(base, buildPreconnectHtml(origins), pageHost = null, timeoutMs = PRECONNECT_SETTLE_MS)
                        }
                    }
                }
            } finally {
                destroyWebView()
            }
        }
    }

    private suspend fun warmSingleGame(target: WarmTarget) {
        val index = kotlinx.coroutines.withContext(Dispatchers.IO) {
            PageCache.load(appCtx, target.id)
                ?: runCatching { PageCache.preloadGame(appCtx, target.id, target.url, target.expiresAt) }.getOrNull()
        }
        if (paused || !PageCache.enabled(appCtx)) return

        val effectiveUrl = index?.finalUrl ?: target.url
        val pageHost = runCatching { java.net.URL(effectiveUrl).host.lowercase(Locale.US) }.getOrNull()
        if (index != null && index.entries.isNotEmpty()) {
            val resources = index.entries.map { (u, e) -> WarmResource(u, e.mime, e.isModule) }
            val origins = if (index.origins.isNotEmpty()) index.origins else listOfNotNull(PageCache.originOf(effectiveUrl))
            val html = buildWarmerHtml(resources, origins)
            val completed = loadHtmlAndAwait(effectiveUrl, html, pageHost, PER_GAME_TIMEOUT_MS)
            if (completed && !paused) {
                warmedCycles[target.id] = target.cycleKey
            }
        } else {
            // Even when static HTTP fetch is challenged (e.g. Cloudflare), preconnect DNS + TLS in Chromium.
            val origin = PageCache.originOf(effectiveUrl) ?: return
            loadHtmlAndAwait(effectiveUrl, buildPreconnectHtml(listOf(origin)), pageHost, PRECONNECT_SETTLE_MS)
        }
    }

    private suspend fun loadHtmlAndAwait(
        baseUrl: String,
        html: String,
        pageHost: String?,
        timeoutMs: Long
    ): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        if (paused) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val wv = ensureWebView()
        if (wv == null) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val token = Any()
        var done = false
        val hostCache = ConcurrentHashMap<String, Boolean>()
        val blockAds = GamePrefs.blockAds(appCtx) && (pageHost == null || !GamePrefs.isSiteAllowed(appCtx, pageHost))

        fun finish(ok: Boolean) {
            if (done) return
            done = true
            main.removeCallbacksAndMessages(token)
            runCatching { wv.stopLoading() }
            if (cont.isActive) cont.resume(ok)
        }

        cont.invokeOnCancellation {
            main.post {
                main.removeCallbacksAndMessages(token)
                runCatching { wv.stopLoading() }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val req = request ?: return blockedResponse()
                if (req.isForMainFrame) return null
                val u = req.url ?: return blockedResponse()
                val scheme = u.scheme?.lowercase(Locale.US)
                if (scheme != "http" && scheme != "https") return blockedResponse()
                val hostKey = u.host?.trim('[', ']')?.lowercase(Locale.US)?.trimEnd('.').orEmpty()
                if (hostKey.isEmpty()) return blockedResponse()
                val urlStr = u.toString()
                val allowedHost = hostCache.getOrPut(hostKey) { SafeHttp.isAllowedForScrape(urlStr) }
                if (!allowedHost) return blockedResponse()
                if (blockAds) {
                    val engine = AdBlocker.network(0)
                    val headers = req.requestHeaders
                    val type = AdBlockEngine.inferType(
                        urlStr,
                        headers?.get("Accept"),
                        false,
                        headers?.get("X-Requested-With"),
                        headers?.get("Sec-Fetch-Dest")
                    )
                    if (engine != null && engine.shouldBlock(urlStr, pageHost, type)) return blockedResponse()
                }
                // Returning null lets Chromium fetch over HTTP/2 + Brotli into its native partitioned cache.
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Brief settle after onload so modulepreload child imports also commit to disk cache.
                main.postAtTime({ finish(true) }, token, SystemClock.uptimeMillis() + 180L)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                main.post {
                    if (webView === view) {
                        webView = null
                        runCatching {
                            (view?.parent as? ViewGroup)?.removeView(view)
                            view?.destroy()
                        }
                    }
                    finish(false)
                }
                return true
            }
        }

        main.postAtTime({ finish(false) }, token, SystemClock.uptimeMillis() + timeoutMs)
        runCatching {
            wv.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
        }.onFailure { finish(false) }
    }

    private fun ensureWebView(): WebView? {
        webView?.let { return it }
        // Keep completely detached from the Activity view tree so view traversal, insets, and input never see it.
        val created = runCatching { WebView(host.context) }.getOrNull() ?: return null
        created.apply {
            visibility = View.GONE
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            settings.apply {
                // Never execute JavaScript in the warmer: zero timer starts, zero storage mutations, zero audio.
                javaScriptEnabled = false
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = true
                blockNetworkImage = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                userAgentString = userAgentString.replace("; wv", "")
            }
        }
        webView = created
        return created
    }

    private fun destroyWebView() {
        val wv = webView ?: return
        webView = null
        runCatching {
            wv.stopLoading()
            wv.onPause()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
    }

    private fun blockedResponse() =
        WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    class WarmResource(val url: String, val mime: String, val isModule: Boolean = false)

    companion object {
        private const val MAX_GAMES_PER_PASS = 8
        private const val MAX_PRECONNECT_ORIGINS = 6
        private const val PER_GAME_TIMEOUT_MS = 4_000L
        private const val PRECONNECT_SETTLE_MS = 900L
        private const val PRECONNECT_COOLDOWN_MS = 45_000L

        private val warmedCycles = ConcurrentHashMap<String, String>()

        /** Whether [gameId] has completed Chromium-native HTTP cache warming for [cycleKey] (or any active cycle when null). */
        fun isNativelyWarmed(gameId: String, cycleKey: String? = null): Boolean {
            val warmed = warmedCycles[gameId] ?: return false
            return cycleKey == null || warmed == cycleKey
        }

        fun forgetGame(gameId: String) {
            warmedCycles.remove(gameId)
        }

        fun clearWarmedState() {
            warmedCycles.clear()
        }

        private fun collectReadyTargets(
            games: JSONArray,
            nowMs: Long,
            localTimeZone: TimeZone = TimeZone.getDefault()
        ): List<WarmTarget> {
            val out = ArrayList<WarmTarget>()
            for (i in 0 until games.length()) {
                val g = games.optJSONObject(i) ?: continue
                val id = g.optString("id")
                if (id.isEmpty() || !GameStore.hasPreloadableUrl(g)) continue
                val resetTime = g.optString("resetTime", "00:00")
                val resetZone = g.optString("resetZone", "local")
                val cycle = GameStore.cycleKey(resetTime, resetZone, nowMs, localTimeZone)
                val status = g.optString("status")
                val doneForCycle = g.optString("loggedCycle") == cycle ||
                    g.optString("lastPlayedCycle") == cycle ||
                    ((status == "completed" || status == "failed" || status == "missed") && g.optString("lastCycleKey") == cycle)
                if (doneForCycle) continue
                val expires = nowMs + GameStore.msUntilNextReset(resetTime, resetZone, nowMs, localTimeZone)
                out += WarmTarget(id, g.optString("url"), cycle, expires)
            }
            return out
        }

        /** Minimal script-free HTML that warms DNS/TLS sockets and Chromium's HTTP/module cache for a game. */
        fun buildWarmerHtml(resources: Collection<WarmResource>, origins: Collection<String>): String {
            val sb = StringBuilder(2048)
            sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
            origins.distinct().take(6).forEach { origin ->
                val esc = escapeAttr(origin)
                sb.append("<link rel=\"preconnect\" href=\"").append(esc).append("\">")
                sb.append("<link rel=\"preconnect\" href=\"").append(esc).append("\" crossorigin>")
            }
            resources.take(64).forEach { r ->
                val esc = escapeAttr(r.url)
                val pathLower = r.url.substringBefore('?').lowercase(Locale.US)
                when {
                    r.mime == "text/css" || pathLower.endsWith(".css") ->
                        sb.append("<link rel=\"stylesheet\" href=\"").append(esc).append("\">")
                    r.mime.startsWith("font/") || pathLower.endsWith(".woff2") || pathLower.endsWith(".woff") || pathLower.endsWith(".ttf") || pathLower.endsWith(".otf") ->
                        sb.append("<link rel=\"preload\" as=\"font\" href=\"").append(esc).append("\" crossorigin>")
                    r.mime == "application/json" || pathLower.endsWith(".json") || pathLower.endsWith(".geojson") ->
                        sb.append("<link rel=\"preload\" as=\"fetch\" href=\"").append(esc).append("\" crossorigin>")
                    r.isModule || pathLower.endsWith(".mjs") || "/assets/" in pathLower || "/_app/immutable/" in pathLower || "/_astro/" in pathLower ->
                        sb.append("<link rel=\"modulepreload\" href=\"").append(esc).append("\">")
                    r.mime.contains("javascript") || pathLower.endsWith(".js") ->
                        sb.append("<link rel=\"preload\" as=\"script\" href=\"").append(esc).append("\">")
                }
            }
            sb.append("</head><body></body></html>")
            return sb.toString()
        }

        /** Minimal HTML that opens DNS + TLS/HTTP2 connections for Ready game origins. */
        fun buildPreconnectHtml(origins: Collection<String>): String {
            val sb = StringBuilder(512)
            sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
            origins.distinct().take(MAX_PRECONNECT_ORIGINS).forEach { origin ->
                val esc = escapeAttr(origin)
                sb.append("<link rel=\"preconnect\" href=\"").append(esc).append("\">")
                sb.append("<link rel=\"preconnect\" href=\"").append(esc).append("\" crossorigin>")
            }
            sb.append("</head><body></body></html>")
            return sb.toString()
        }

        private fun escapeAttr(s: String): String = buildString(s.length) {
            for (c in s) {
                when (c) {
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    else -> append(c)
                }
            }
        }
    }
}
