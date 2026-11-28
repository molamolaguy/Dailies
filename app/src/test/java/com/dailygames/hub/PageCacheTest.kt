package com.dailygames.hub

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

class PageCacheTest {
    @Test
    fun findsScriptsStylesheetsAndPreloadsInPageOrder() {
        val html = """
            <html><head>
            <link rel="icon" href="/favicon.ico">
            <link rel="stylesheet" href="/static/app.css?v=3">
            <script src="https://cdn.example.net/lib.js" defer></script>
            <link rel="preload" as="font" href="fonts/a.woff2" crossorigin>
            <link rel="preload" as="image" href="/hero.png">
            <link rel="modulepreload" href="./chunk.mjs">
            <script>inline()</script>
            <script type="module" src='/main.js?x=1&amp;y=2#frag'></script>
            <script src="data:text/javascript,1"></script>
            </head></html>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://game.example.com/static/app.css?v=3",
                "https://cdn.example.net/lib.js",
                "https://game.example.com/play/fonts/a.woff2",
                "https://game.example.com/play/chunk.mjs",
                "https://game.example.com/main.js?x=1&y=2"
            ),
            PageCache.subresources(html, "https://game.example.com/play/index.html")
        )
        assertEquals(
            setOf(
                "https://game.example.com/play/chunk.mjs",
                "https://game.example.com/main.js?x=1&y=2"
            ),
            PageCache.moduleResources(html, "https://game.example.com/play/index.html")
        )
    }

    @Test
    fun aBaseTagChangesWhereRelativeFilesAre() {
        val html = """<base href="https://assets.example.org/v2/"><script src="app.js"></script>"""
        assertEquals(listOf("https://assets.example.org/v2/app.js"), PageCache.subresources(html, "https://example.org/"))
    }

    @Test
    fun stylesheetFontsAreFound() {
        val css = """@font-face{src:url("../fonts/x.woff2") format("woff2"),url(y.woff?v=1)} .a{background:url(bg.png)}"""
        assertEquals(
            listOf("https://e.com/fonts/x.woff2", "https://e.com/css/y.woff?v=1"),
            PageCache.cssFonts(css, "https://e.com/css/site.css")
        )
    }

    @Test
    fun esModuleImportsAndNextBuildManifestChunksAreDiscovered() {
        val viteJs = """
            import{r as react}from"./vendor-a1b2.js";
            import "/assets/theme-c3d4.mjs";
            export * from "../shared/utils-e5f6.js";
            const lazy = () => import("./puzzle-data-7890.js");
        """.trimIndent()
        assertEquals(
            listOf(
                "https://farflung.example/assets/vendor-a1b2.js",
                "https://farflung.example/assets/theme-c3d4.mjs",
                "https://farflung.example/shared/utils-e5f6.js",
                "https://farflung.example/assets/puzzle-data-7890.js"
            ),
            PageCache.esModuleImports(viteJs, "https://farflung.example/assets/index-0001.js")
        )

        val nextManifest = """self.__BUILD_MANIFEST={sortedPages:["/"],"/":["static/chunks/pages/index-9a8b.js","static/chunks/421-ff01.js"]}"""
        assertEquals(
            listOf(
                "https://framed.example/_next/static/chunks/pages/index-9a8b.js",
                "https://framed.example/_next/static/chunks/421-ff01.js"
            ),
            PageCache.esModuleImports(nextManifest, "https://framed.example/_next/static/build123/_buildManifest.js")
        )
    }

    @Test
    fun contentTypesSplitIntoMimeAndCharset() {
        assertEquals("text/javascript" to "utf-8", PageCache.splitType("text/javascript; charset=UTF-8".replace("UTF-8", "utf-8"), "a.js"))
        assertEquals("text/css" to "utf-8", PageCache.splitType("text/css", "a.css"))
        assertEquals("font/woff2" to null, PageCache.splitType(null, "https://e.com/a.woff2?v=2"))
        assertEquals("application/javascript" to "utf-8", PageCache.splitType("application/javascript", "x"))
        assertEquals("application/json" to "utf-8", PageCache.splitType(null, "https://e.com/data/map.geojson"))
    }

    @Test
    fun corsAllowsValidatesOriginAgainstCachedHeaderAndPermitsSameOriginModules() {
        val dummy = File("dummy")
        val noCors = PageCache.Entry(dummy, "text/javascript", "utf-8", emptyMap(), isModule = true)
        val wildcard = PageCache.Entry(dummy, "text/javascript", "utf-8", mapOf("access-control-allow-origin" to "*"))
        val specific = PageCache.Entry(dummy, "text/javascript", "utf-8", mapOf("access-control-allow-origin" to "https://Game.Example"))

        assertTrue(PageCache.corsAllows(noCors, null))
        assertFalse(PageCache.corsAllows(noCors, mapOf("Origin" to "https://game.example")))
        // Same-origin ES module requests carry Origin: https://game.example without needing Access-Control-Allow-Origin.
        assertTrue(PageCache.corsAllows(noCors, mapOf("Origin" to "https://game.example"), "https://game.example/assets/index.js"))
        assertFalse(PageCache.corsAllows(noCors, mapOf("Origin" to "https://game.example"), "https://cdn.other.example/assets/index.js"))
        assertTrue(PageCache.corsAllows(wildcard, mapOf("Origin" to "https://game.example")))
        assertTrue(PageCache.corsAllows(specific, mapOf("origin" to "https://game.example")))
        assertFalse(PageCache.corsAllows(specific, mapOf("Origin" to "https://other.example")))
    }

    @Test
    fun responseHeadersPreserveImmutableCacheControlInsteadOfNoCache() {
        val dummy = File("dummy")
        val immutableEntry = PageCache.Entry(
            dummy,
            "text/javascript",
            "utf-8",
            mapOf("cache-control" to "public, max-age=31536000, immutable", "etag" to "\"v1\"")
        )
        assertTrue(PageCache.isBrowserCacheable(immutableEntry))
        val headers1 = PageCache.responseHeaders(immutableEntry, expiresAt = 200_000L, nowMs = 100_000L)
        assertEquals("public, max-age=31536000, immutable", headers1["Cache-Control"])
        assertEquals("\"v1\"", headers1["etag"])

        val revalidateEntry = PageCache.Entry(dummy, "text/javascript", "utf-8", mapOf("cache-control" to "no-cache"))
        assertFalse(PageCache.isBrowserCacheable(revalidateEntry))
        val headers2 = PageCache.responseHeaders(revalidateEntry, expiresAt = 700_000L, nowMs = 100_000L)
        assertEquals("public, max-age=600", headers2["Cache-Control"])
    }

    @Test
    fun uncachedReadyIdsFindsUnplayedGamesIncludingMutedOnesAndSkipsCompletedOrArchived() {
        val nowMs = 1773576000000L // 2026-03-15 12:00:00 UTC
        val utc = TimeZone.getTimeZone("UTC")
        val games = JSONArray(
            """
            [
              {"id": "ready_muted", "url": "https://worldle.example/", "resetTime": "00:00", "resetZone": "utc", "notifyEnabled": false},
              {"id": "ready_cached", "url": "https://globle.example/", "resetTime": "00:00", "resetZone": "utc", "notifyEnabled": true},
              {"id": "done_today", "url": "https://wordle.example/", "resetTime": "00:00", "resetZone": "utc", "loggedCycle": "2026-03-15"},
              {"id": "archived_game", "url": "https://old.example/", "resetTime": "00:00", "resetZone": "utc", "archived": true},
              {"id": "non_daily", "url": "https://infinite.example/", "resetTime": "00:00", "resetZone": "utc", "nonDaily": true}
            ]
            """.trimIndent()
        )
        val missing = PageCache.uncachedReadyIds(
            games = games,
            isCached = { it == "ready_cached" },
            recentlyAttempted = { false },
            nowMs = nowMs,
            localTimeZone = utc
        )
        assertEquals(listOf("ready_muted"), missing)
    }

    @Test
    fun warmerHtmlEmitsScriptFreePreconnectModulepreloadAndPreloadLinks() {
        val html = GamePrewarmer.buildWarmerHtml(
            resources = listOf(
                GamePrewarmer.WarmResource("https://g.example/assets/app.js", "text/javascript", isModule = true),
                GamePrewarmer.WarmResource("https://g.example/static/legacy.js", "text/javascript", isModule = false),
                GamePrewarmer.WarmResource("https://g.example/site.css", "text/css"),
                GamePrewarmer.WarmResource("https://cdn.example/f.woff2", "font/woff2"),
                GamePrewarmer.WarmResource("https://g.example/data/map.geojson", "application/json")
            ),
            origins = listOf("https://g.example", "https://cdn.example")
        )
        assertFalse(html.contains("<script", ignoreCase = true))
        assertTrue(html.contains("""<link rel="preconnect" href="https://g.example" crossorigin>"""))
        assertTrue(html.contains("""<link rel="modulepreload" href="https://g.example/assets/app.js">"""))
        assertTrue(html.contains("""<link rel="preload" as="script" href="https://g.example/static/legacy.js">"""))
        assertTrue(html.contains("""<link rel="stylesheet" href="https://g.example/site.css">"""))
        assertTrue(html.contains("""<link rel="preload" as="font" href="https://cdn.example/f.woff2" crossorigin>"""))
        assertTrue(html.contains("""<link rel="preload" as="fetch" href="https://g.example/data/map.geojson" crossorigin>"""))
    }
}
