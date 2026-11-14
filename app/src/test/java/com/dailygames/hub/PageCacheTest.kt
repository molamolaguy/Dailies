package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Test

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
    fun contentTypesSplitIntoMimeAndCharset() {
        assertEquals("text/javascript" to "utf-8", PageCache.splitType("text/javascript; charset=UTF-8".replace("UTF-8", "utf-8"), "a.js"))
        assertEquals("text/css" to "utf-8", PageCache.splitType("text/css", "a.css"))
        assertEquals("font/woff2" to null, PageCache.splitType(null, "https://e.com/a.woff2?v=2"))
        assertEquals("application/javascript" to "utf-8", PageCache.splitType("application/javascript", "x"))
    }
}
