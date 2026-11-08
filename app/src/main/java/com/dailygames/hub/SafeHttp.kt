package com.dailygames.hub

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal, defensive HTTP GET used for fetching game metadata and icons.
 *
 * - Only http/https, and only to public internet addresses: loopback, private (10/8, 172.16/12,
 *   192.168/16), link-local, carrier-grade NAT (100.64/10), multicast and IPv6 unique-local hosts
 *   are refused, so a web page can't make the app probe the user's local network.
 * - Redirects are followed manually (max 5) so every hop is checked the same way.
 * - No DNS rebinding: a host is resolved once and checked, then plain http connects to that checked
 *   address (with the original Host header), and https verifies the address the socket actually
 *   connected to before the TLS handshake. Connections are direct (no proxy) so the check is meaningful.
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
            val pinned = if (allowPrivateHostsForTests) null else (resolvePublic(u.host) ?: return null)
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            var conn: HttpURLConnection? = null
            try {
                conn = open(u, pinned).apply {
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

    /** Opens a direct connection to [u]; with [pinned] addresses, only to those checked addresses. */
    private fun open(u: URL, pinned: List<InetAddress>?): HttpURLConnection {
        if (pinned == null) return u.openConnection() as HttpURLConnection
        if (u.protocol.equals("https", ignoreCase = true)) {
            // TLS needs the real host name (SNI, certificate check), so connect by name and verify the
            // address the socket really reached; a rebinding answer to a private address is refused.
            return (u.openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
                sslSocketFactory = PublicOnlySocketFactory(sslSocketFactory)
            }
        }
        // Prefer IPv4: some networks advertise IPv6 that doesn't actually route.
        val addr = pinned.firstOrNull { it is Inet4Address } ?: pinned.first()
        val conn = pinnedHttpUrl(u, addr).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.setRequestProperty("Host", if (u.port == -1 || u.port == u.defaultPort) u.host else "${u.host}:${u.port}")
        return conn
    }

    /** [u] with its host replaced by the literal address [addr] (IPv6 in brackets), for plain http. */
    fun pinnedHttpUrl(u: URL, addr: InetAddress): URL {
        val literal = addr.hostAddress.orEmpty().substringBefore('%').let { if (addr is Inet6Address) "[$it]" else it }
        return URL(u.protocol, literal, u.port, u.file)
    }

    /** Delegating TLS socket factory that refuses sockets connected to a non-public address. */
    private class PublicOnlySocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
        private fun checked(s: Socket): Socket {
            val a = s.inetAddress
            if (a == null || !isPublicAddress(a)) {
                runCatching { s.close() }
                throw IOException("Refused connection to a non-public address")
            }
            return s
        }

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(s: Socket, host: String?, port: Int, autoClose: Boolean): Socket {
            if (s.inetAddress == null || !isPublicAddress(s.inetAddress)) {
                runCatching { s.close() }
                throw IOException("Refused connection to a non-public address")
            }
            return delegate.createSocket(s, host, port, autoClose)
        }
        override fun createSocket(host: String?, port: Int): Socket = checked(delegate.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            checked(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket = checked(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            checked(delegate.createSocket(address, port, localAddress, localPort))
    }

    /** True if every address [host] resolves to is a public internet address. */
    fun isPublicHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        if (allowPrivateHostsForTests) return true
        return resolvePublic(host) != null
    }

    /** The addresses [host] resolves to, or null if it is a local name or any address isn't public. */
    fun resolvePublic(host: String?): List<InetAddress>? {
        if (host.isNullOrBlank()) return null
        val h = host.trim('[', ']').lowercase(Locale.US)
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) return null
        val addrs = runCatching { InetAddress.getAllByName(h) }.getOrNull() ?: return null
        return addrs.toList().takeIf { it.isNotEmpty() && it.all { a -> isPublicAddress(a) } }
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
            val b2 = b[2].toInt() and 0xff
            if ((b0 == 192 && b1 == 0 && b2 == 2) || (b0 == 198 && b1 == 51 && b2 == 100) || (b0 == 203 && b1 == 0 && b2 == 113)) {
                return false                                       // documentation ranges
            }
        }
        if (a is Inet6Address && b.size == 16) {
            val u = IntArray(16) { b[it].toInt() and 0xff }
            if (u[0] == 0xfc || u[0] == 0xfd) return false           // unique local fc00::/7
            fun embedded(from: Int) = isPublicAddress(InetAddress.getByAddress(b.copyOfRange(from, from + 4)))
            // IPv4-mapped ::ffff:a.b.c.d and the deprecated IPv4-compatible ::a.b.c.d
            if ((0 until 10).all { u[it] == 0 } && ((u[10] == 0xff && u[11] == 0xff) || (u[10] == 0 && u[11] == 0))) return embedded(12)
            // NAT64 64:ff9b::a.b.c.d (how IPv6-only networks reach IPv4: check the IPv4 target)
            if (u[0] == 0x00 && u[1] == 0x64 && u[2] == 0xff && u[3] == 0x9b && (4 until 12).all { u[it] == 0 }) return embedded(12)
            // 6to4 2002:a.b.c.d::/48
            if (u[0] == 0x20 && u[1] == 0x02) return embedded(2)
            if (u[0] == 0x20 && u[1] == 0x01 && u[2] == 0x00 && u[3] == 0x00) return false // Teredo 2001::/32
            if (u[0] == 0x20 && u[1] == 0x01 && u[2] == 0x0d && u[3] == 0xb8) return false // documentation 2001:db8::/32
            if (u[0] == 0x01 && u[1] == 0x00 && (2 until 8).all { u[it] == 0 }) return false // discard 100::/64
        }
        return true
    }
}
