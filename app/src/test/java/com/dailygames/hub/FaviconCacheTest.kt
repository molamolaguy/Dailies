package com.dailygames.hub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FaviconCacheTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    @Test
    fun downloadsOnceThenServesFromDiskOffline() {
        val dir = Files.createTempDirectory("fav").toFile()
        var calls = 0
        val online = FaviconCache(dir, fetcher = { calls++; png to "image/png" })
        val url = "https://www.google.com/s2/favicons?sz=128&domain_url=https%3A%2F%2Ffe-guess.com"

        assertArrayEquals(png, online.load(url)!!.bytes)
        assertArrayEquals(png, online.load(url)!!.bytes)
        assertEquals(1, calls)
        assertTrue(online.isCached(url))

        // A new instance with no network still has the icon.
        val offline = FaviconCache(dir, fetcher = { null })
        val icon = offline.load(url)!!
        assertArrayEquals(png, icon.bytes)
        assertEquals("image/png", icon.mimeType)
    }

    @Test
    fun rejectsNonImagesNonHttpAndFailures() {
        val dir = Files.createTempDirectory("fav").toFile()
        assertNull(FaviconCache(dir, fetcher = { "<html>".toByteArray() to "text/html" }).load("https://example.com/x"))
        assertNull(FaviconCache(dir, fetcher = { png to "image/png" }).load("file:///etc/passwd"))
        assertNull(FaviconCache(dir, fetcher = { png to "image/png" }).load("javascript:alert(1)"))
        assertNull(FaviconCache(dir, fetcher = { throw RuntimeException("boom") }).load("https://example.com/y"))
        assertFalse(FaviconCache(dir, fetcher = { null }).isCached("https://example.com/x"))
    }

    @Test
    fun sniffsCommonFormats() {
        assertEquals("image/png", FaviconCache.sniffMime(png))
        assertEquals("image/x-icon", FaviconCache.sniffMime(byteArrayOf(0, 0, 1, 0, 1)))
        assertEquals("image/svg+xml", FaviconCache.sniffMime("<?xml version=\"1.0\"?><svg></svg>".toByteArray()))
        assertEquals("", FaviconCache.sniffMime("hello".toByteArray()))
    }

    @Test
    fun failedDownloadsBackOffInsteadOfRetryingOnEveryRequest() {
        val dir = Files.createTempDirectory("fav").toFile()
        var now = 1_000_000L
        var calls = 0
        var online = false
        val cache = FaviconCache(dir, { calls++; if (online) png to "image/png" else null }, { now })
        val url = "https://example.com/icon.png"
        assertNull(cache.load(url))
        online = true
        assertNull("within the 10 min backoff", cache.load(url))
        assertEquals(1, calls)
        now += 11 * 60 * 1000
        assertArrayEquals(png, cache.load(url)!!.bytes)
        assertEquals(2, calls)
    }
}
