package com.dailygames.hub

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.util.Locale

/**
 * Minimal, defensive HTTP GET used for fetching game metadata and icons.
 *
 * - Only http/https, and only to public internet addresses: loopback, private (10/8, 172.16/12,
 *   192.168/16), link-local, carrier-grade NAT (100.64/10), multicast and IPv6 unique-local hosts
 *   are refused, so a web page can't make the app probe the user's local network.
 * - Redirects are followed manually (max 5) so every hop is checked the same way.
 * - Bounded: an overall deadline and a maximum body size.
 */
object SafeHttp {
    class Response(val code: Int, val finalUrl: String, val contentType: String?, val body: ByteArray)

    /** For unit tests that run against a local test server. */
    @Volatile var allowPrivateHostsForTests = false

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int,
        deadlineMs: Long = 10_000,
        maxRedirects: Int = 5
    ): Response? {
        val deadline = System.currentTimeMillis() + deadlineMs
        var current = url
        repeat(maxRedirects + 1) {
            val u = runCatching { URL(current) }.getOrNull() ?: return null
            val scheme = u.protocol.lowercase(Locale.US)
            if (scheme != "http" && scheme != "https") return null
            if (!isPublicHost(u.host)) return null
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            var conn: HttpURLConnection? = null
            try {
                conn = (u.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = remaining.coerceAtMost(6000).toInt()
                    readTimeout = remaining.coerceAtMost(6000).toInt()
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = URL(u, loc).toString()
                    return@repeat
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val out = ByteArrayOutputStream()
                stream?.use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        if (System.currentTimeMillis() > deadline) return null
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        if (out.size() >= maxBytes) break
                    }
                }
                return Response(code, current, conn.contentType, out.toByteArray())
            } catch (e: Exception) {
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    /** True if every address [host] resolves to is a public internet address. */
    fun isPublicHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        if (allowPrivateHostsForTests) return true
        val h = host.trim('[', ']').lowercase(Locale.US)
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) return false
        val addrs = runCatching { InetAddress.getAllByName(h) }.getOrNull() ?: return false
        return addrs.isNotEmpty() && addrs.all { isPublicAddress(it) }
    }

    fun isPublicAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        if (a is Inet4Address) {
            val b0 = b[0].toInt() and 0xff
            val b1 = b[1].toInt() and 0xff
            if (b0 == 0 || b0 >= 240) return false                 // "this network", reserved
            if (b0 == 100 && b1 in 64..127) return false          // carrier-grade NAT
            if (b0 == 192 && b1 == 0 && (b[2].toInt() and 0xff) == 0) return false
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false // benchmarking
        }
        if (a is Inet6Address) {
            val b0 = b[0].toInt() and 0xff
            if (b0 == 0xfc || b0 == 0xfd) return false            // unique local fc00::/7
            // IPv4-mapped ::ffff:a.b.c.d -> check the embedded IPv4 address
            if (b.size == 16 && b.take(10).all { it.toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff) {
                return isPublicAddress(InetAddress.getByAddress(b.copyOfRange(12, 16)))
            }
        }
        return true
    }
}
