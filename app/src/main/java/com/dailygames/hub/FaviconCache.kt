package com.dailygames.hub

import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Stores game icons on the device so the hub shows them instantly and offline.
 *
 * The hub requests icons as `https://favicon.dailes.invalid/?src=<original url>`; HubActivity's
 * WebViewClient intercepts that host (it never reaches the network) and answers from [load]. `.invalid`
 * is a reserved TLD, so the address can never belong to a real site.
 *
 * - Downloads go through [SafeHttp] (public hosts only, short deadline, size cap).
 * - Failed downloads are remembered for [FAILURE_BACKOFF_MS] so dead icons don't tie up the WebView.
 * - Icons older than [MAX_AGE_MS] are still served instantly but refreshed in the background.
 * - The cache is capped at [MAX_CACHE_BYTES]; the least recently used icons are evicted.
 */
class FaviconCache(
    private val dir: File,
    private val fetcher: (String) -> Pair<ByteArray, String>? = ::download,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Icon(val bytes: ByteArray, val mimeType: String)

    private val locks = Array(64) { Any() }
    private val failures = ConcurrentHashMap<String, Long>()
    private val refresher = Executors.newSingleThreadExecutor { r -> Thread(r, "favicon-refresh").apply { isDaemon = true } }

    /** Returns the cached icon, downloading and saving it first if needed; null if unavailable. */
    fun load(sourceUrl: String): Icon? {
        if (!isFetchable(sourceUrl)) return null
        val key = keyFor(sourceUrl)
        synchronized(locks[(key.hashCode() and 0x7fffffff) % locks.size]) {
            readCached(key)?.let { cached ->
                if (clock() - dataFile(key).lastModified() > MAX_AGE_MS) refreshLater(sourceUrl, key)
                return cached
            }
            val failedAt = failures[key]
            if (failedAt != null && clock() - failedAt < FAILURE_BACKOFF_MS) return null
            val icon = fetchAndStore(sourceUrl, key)
            if (icon == null) failures[key] = clock() else failures.remove(key)
            return icon
        }
    }

    fun isCached(sourceUrl: String): Boolean = isFetchable(sourceUrl) && dataFile(keyFor(sourceUrl)).exists()

    private fun fetchAndStore(sourceUrl: String, key: String): Icon? {
        val fetched = runCatching { fetcher(sourceUrl) }.getOrNull() ?: return null
        val (bytes, mime) = fetched
        if (bytes.isEmpty() || bytes.size > MAX_BYTES || !mime.startsWith("image/")) return null
        save(key, bytes, mime)
        return Icon(bytes, mime)
    }

    private fun refreshLater(sourceUrl: String, key: String) {
        runCatching {
            refresher.execute {
                synchronized(locks[(key.hashCode() and 0x7fffffff) % locks.size]) { fetchAndStore(sourceUrl, key) }
            }
        }
    }

    private fun readCached(key: String): Icon? {
        val data = dataFile(key)
        if (!data.exists()) return null
        return try {
            val mime = File(dir, "$key.type").takeIf { it.exists() }?.readText()?.trim().orEmpty().ifEmpty { "image/png" }
            Icon(data.readBytes(), mime).also {
                // Touch for LRU (keeps the original download time in the .type file's mtime).
                File(dir, "$key.used").writeText("")
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun save(key: String, bytes: ByteArray, mime: String) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "$key.tmp")
            FileOutputStream(tmp).use { it.write(bytes) }
            File(dir, "$key.type").writeText(mime)
            val dest = dataFile(key)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) tmp.delete()
            File(dir, "$key.used").writeText("")
            trim()
        } catch (e: Exception) {
            // Not fatal: the icon is still shown this time and fetched again next time.
        }
    }

    /** Evicts least-recently-used icons once the cache exceeds [MAX_CACHE_BYTES]. */
    private fun trim() {
        val images = dir.listFiles { f -> f.name.endsWith(".img") } ?: return
        var total = images.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        val byUse = images.sortedBy { File(dir, it.name.removeSuffix(".img") + ".used").lastModified() }
        for (f in byUse) {
            if (total <= MAX_CACHE_BYTES * 3 / 4) break
            val k = f.name.removeSuffix(".img")
            total -= f.length()
            f.delete()
            File(dir, "$k.type").delete()
            File(dir, "$k.used").delete()
        }
    }

    private fun dataFile(key: String) = File(dir, "$key.img")

    companion object {
        const val INTERCEPT_HOST = "favicon.dailes.invalid"
        private const val MAX_BYTES = 512 * 1024
        private const val MAX_CACHE_BYTES = 20L * 1024 * 1024
        private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
        private const val FAILURE_BACKOFF_MS = 10L * 60 * 1000

        fun isFetchable(url: String): Boolean {
            val scheme = runCatching { URI(url).scheme?.lowercase(Locale.US) }.getOrNull()
            return scheme == "https" || scheme == "http"
        }

        fun keyFor(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray(Charsets.UTF_8))
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }

        /** Downloads an image via [SafeHttp] (public hosts only, 5 s deadline, size cap). */
        fun download(url: String): Pair<ByteArray, String>? {
            val resp = SafeHttp.get(
                url,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android) Dailies/1.0", "Accept" to "image/*"),
                maxBytes = MAX_BYTES + 1,
                deadlineMs = 5000
            ) ?: return null
            if (resp.code !in 200..299 || resp.body.size > MAX_BYTES) return null
            val mime = resp.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
            return resp.body to (if (mime.startsWith("image/")) mime else sniffMime(resp.body))
        }

        /** Some servers send icons as application/octet-stream; recognise the common formats. */
        fun sniffMime(bytes: ByteArray): String = when {
            bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes.size >= 4 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
            bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte() -> "image/x-icon"
            bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            String(bytes.copyOfRange(0, minOf(bytes.size, 256)), Charsets.UTF_8).contains("<svg") -> "image/svg+xml"
            else -> ""
        }
    }
}
