package com.dailygames.hub

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
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
 * - No DNS rebinding: a host is resolved once and checked, then both plain http and https connect
 *   directly to that checked address (keeping the original hostname for Host / SNI / TLS cert check).
 * - Proxies: when the phone has an HTTP proxy set (Wi-Fi or VPN proxy settings), requests go through it,
 *   as the WebView's do. The proxy resolves names itself, so the app can't pin addresses; it still refuses
 *   local names and literal private addresses, and a name that resolves locally must resolve to public
 *   addresses. Without a proxy, connections are direct and pinned as above.
 * - Bounded: an overall deadline and a maximum body size.
 */
object SafeHttp {
    class Response(
        val code: Int, val finalUrl: String, val contentType: String?, val body: ByteArray,
        /** Response headers, names lower-cased, first value of each (1.6.2). */
        val headers: Map<String, String> = emptyMap()
    )

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private val SENSITIVE_HEADERS = setOf("cookie", "authorization", "proxy-authorization")
    private val LOCAL_TLDS = setOf(".localhost", ".local", ".internal", ".corp", ".lan", ".home", ".arpa")

    /** For unit tests that run against a local test server. */
    @Volatile var allowPrivateHostsForTests = false

    /** For unit tests: the proxy to use instead of the system's (null = ask the system). */
    @Volatile var proxyForTests: Proxy? = null

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int,
        deadlineMs: Long = 10_000,
        maxRedirects: Int = 5
    ): Response? {
        val deadline = System.currentTimeMillis() + deadlineMs
        var current = url
        val initialOrigin = originKey(runCatching { URL(url) }.getOrNull())
        var activeHeaders = headers
        repeat(maxRedirects + 1) {
            val u = runCatching { URL(current) }.getOrNull() ?: return null
            val scheme = u.protocol.lowercase(Locale.US)
            if (scheme != "http" && scheme != "https") return null
            val currentOrigin = originKey(u)
            if (initialOrigin != null && currentOrigin != initialOrigin) {
                activeHeaders = activeHeaders.filterKeys { it.lowercase(Locale.US) !in SENSITIVE_HEADERS }
            }
            val proxy = proxyFor(u)
            val pinned = when {
                proxy != null -> { if (!allowPrivateHostsForTests && !proxiedHostAllowed(u.host)) return null; null }
                allowPrivateHostsForTests -> null
                else -> resolvePublic(u.host) ?: return null
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            var conn: HttpURLConnection? = null
            try {
                val timeoutMs = remaining.coerceAtMost(6000).toInt()
                conn = (if (proxy != null) u.openConnection(proxy) as HttpURLConnection else open(u, pinned, timeoutMs)).apply {
                    instanceFollowRedirects = false
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    activeHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val code = conn.responseCode
                if (code in REDIRECT_CODES) {
                    val loc = conn.getHeaderField("Location")?.trim()?.replace(" ", "%20") ?: return null
                    val nextUrl = runCatching { URL(u, loc) }.getOrNull() ?: return null
                    val nextOrigin = originKey(nextUrl)
                    if (nextOrigin != currentOrigin || (scheme == "https" && nextUrl.protocol.equals("http", true))) {
                        activeHeaders = activeHeaders.filterKeys { it.lowercase(Locale.US) !in SENSITIVE_HEADERS }
                    }
                    current = nextUrl.toString()
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
                val hdrs = HashMap<String, String>()
                conn.headerFields?.forEach { (k, v) -> if (k != null && !v.isNullOrEmpty()) hdrs[k.lowercase(Locale.US)] = v[0] }
                // A body cut off at maxBytes isn't the whole file.
                if (out.size() >= maxBytes) hdrs["x-dailies-truncated"] = "1"
                return Response(code, current, conn.contentType, out.toByteArray(), hdrs)
            } catch (e: Exception) {
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun originKey(u: URL?): Triple<String, String, Int>? {
        if (u == null) return null
        val scheme = u.protocol.lowercase(Locale.US)
        val host = u.host.lowercase(Locale.US).trimEnd('.')
        val port = if (u.port == -1) u.defaultPort else u.port
        return Triple(scheme, host, port)
    }

    /** True if [url] is an http(s) URL allowed under the active direct/proxy SSRF policy. */
    fun isAllowedForScrape(url: String?): Boolean {
        val u = runCatching { URL(url) }.getOrNull() ?: return false
        val scheme = u.protocol.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return false
        if (allowPrivateHostsForTests) return true
        val proxy = proxyFor(u)
        return if (proxy != null) proxiedHostAllowed(u.host) else resolvePublic(u.host) != null
    }

    /** The HTTP proxy the system would use for [u], or null for a direct connection. */
    private fun proxyFor(u: URL): Proxy? {
        proxyForTests?.let { return it }
        val uri = runCatching { u.toURI() }.getOrNull()
            ?: runCatching { URI(u.toString().replace(" ", "%20")) }.getOrNull()
            ?: return null
        val selected = runCatching { java.net.ProxySelector.getDefault()?.select(uri) }.getOrNull().orEmpty()
        return selected.firstOrNull { it.type() == Proxy.Type.HTTP }
    }

    private fun isLocalHostname(h: String): Boolean {
        if (h.isEmpty() || h == "localhost") return true
        return LOCAL_TLDS.any { h.endsWith(it) }
    }

    /**
     * Whether [host] may be fetched through a proxy: never a local name, single-label hostname,
     * or a literal non-public address; a name the phone can resolve must resolve only to public
     * addresses; a multi-label public-TLD name only the proxy can resolve is left to the proxy.
     */
    fun proxiedHostAllowed(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.trim('[', ']').lowercase(Locale.US).trimEnd('.')
        if (isLocalHostname(h)) return false
        if (h.contains(':')) return runCatching { isPublicAddress(InetAddress.getByName(h)) }.getOrDefault(false)
        if (Regex("^[0-9a-fx.]+$").matches(h) && h.split('.').all { Regex("^(0x[0-9a-f]*|[0-9]+)$").matches(it) }) {
            // Numeric IPv4 in any form. Octal ("0177.1") and hex parts mean different addresses to
            // different resolvers, so only plain dotted-decimal is accepted, and only if it's public.
            val parts = h.split('.')
            val canonical = parts.size == 4 && parts.all { it.length in 1..3 && (it == "0" || !it.startsWith("0")) && it.toInt() <= 255 }
            return canonical && runCatching { isPublicAddress(InetAddress.getByName(h)) }.getOrDefault(false)
        }
        if (!h.contains('.')) return false
        val addrs = runCatching { InetAddress.getAllByName(h) }.getOrNull() ?: return true
        return addrs.isNotEmpty() && addrs.all { isPublicAddress(it) }
    }

    /** Opens a direct connection to [u]; with [pinned] addresses, only to those checked addresses. */
    private fun open(u: URL, pinned: List<InetAddress>?, timeoutMs: Int): HttpURLConnection {
        if (pinned == null) return u.openConnection() as HttpURLConnection
        // Prefer IPv4: some networks advertise IPv6 that doesn't actually route.
        val addr = pinned.firstOrNull { it is Inet4Address } ?: pinned.first()
        if (u.protocol.equals("https", ignoreCase = true)) {
            // Connect the TCP socket directly to the validated pinned IP before wrapping with TLS
            // so DNS cannot rebind between resolvePublic() and HttpsURLConnection.connect().
            return (u.openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
                sslSocketFactory = PublicOnlySocketFactory(sslSocketFactory, addr, u.host, timeoutMs)
            }
        }
        val conn = pinnedHttpUrl(u, addr).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.setRequestProperty("Host", if (u.port == -1 || u.port == u.defaultPort) u.host else "${u.host}:${u.port}")
        return conn
    }

    /** [u] with its host replaced by the literal address [addr] (IPv6 in brackets), for plain http. */
    fun pinnedHttpUrl(u: URL, addr: InetAddress): URL {
        val literal = addr.hostAddress.orEmpty().substringBefore('%').let { if (addr is Inet6Address) "[$it]" else it }
        return URL(u.protocol, literal, u.port, u.file)
    }

    /** Delegating TLS socket factory that pins TCP connections to [pinned] (for [originalHost]) and refuses non-public sockets. */
    private class PublicOnlySocketFactory(
        private val delegate: SSLSocketFactory,
        private val pinned: InetAddress,
        private val originalHost: String,
        private val timeoutMs: Int
    ) : SSLSocketFactory() {
        private fun checked(s: Socket): Socket {
            val a = s.inetAddress
            if (a == null || a != pinned || !isPublicAddress(a)) {
                runCatching { s.close() }
                throw IOException("Refused connection to an unpinned or non-public address")
            }
            return s
        }

        private fun connectPinned(port: Int, localAddr: InetAddress? = null, localPort: Int = 0): Socket {
            if (!isPublicAddress(pinned)) throw IOException("Refused connection to a non-public address")
            val raw = Socket()
            try {
                if (localAddr != null) raw.bind(InetSocketAddress(localAddr, localPort))
                raw.connect(InetSocketAddress(pinned, port), timeoutMs.coerceAtLeast(1))
                return delegate.createSocket(raw, originalHost, port, true)
            } catch (e: Throwable) {
                runCatching { raw.close() }
                if (e is IOException) throw e
                throw IOException(e)
            }
        }

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(s: Socket, host: String?, port: Int, autoClose: Boolean): Socket {
            checked(s)
            return delegate.createSocket(s, host ?: originalHost, port, autoClose)
        }
        override fun createSocket(host: String?, port: Int): Socket = connectPinned(port)
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            connectPinned(port, localHost, localPort)
        override fun createSocket(host: InetAddress?, port: Int): Socket {
            if (host == null || host != pinned || !isPublicAddress(host)) throw IOException("Refused connection to an unpinned or non-public address")
            return connectPinned(port)
        }
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
            if (address == null || address != pinned || !isPublicAddress(address)) throw IOException("Refused connection to an unpinned or non-public address")
            return connectPinned(port, localAddress, localPort)
        }
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
        val h = host.trim('[', ']').lowercase(Locale.US).trimEnd('.')
        if (isLocalHostname(h)) return null
        val addrs = runCatching { InetAddress.getAllByName(h) }.getOrNull() ?: return null
        return addrs.toList().takeIf { it.isNotEmpty() && it.all { a -> isPublicAddress(a) } }
    }

    fun isPublicAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        if (a is Inet4Address) {
            val b0 = b[0].toInt() and 0xff
            val b1 = b[1].toInt() and 0xff
            val b2 = b[2].toInt() and 0xff
            if (b0 == 0 || b0 >= 240) return false                 // "this network", reserved
            if (b0 == 100 && b1 in 64..127) return false          // carrier-grade NAT
            if (b0 == 192 && b1 == 0 && b2 == 0) return false
            if (b0 == 192 && b1 == 88 && b2 == 99) return false   // deprecated 6to4 relay anycast 192.88.99.0/24
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false // benchmarking
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
            // Explicit check for IPv4-mapped ::ffff:0:0/96 (bytes 0..9=0, 10..11=ff) in case Inet6Address wrapper wasn't unwrapped
            if ((0 until 10).all { u[it] == 0 } && u[10] == 0xff && u[11] == 0xff) return embedded(12)
            // NAT64 well-known prefix 64:ff9b::a.b.c.d
            if (u[0] == 0x00 && u[1] == 0x64 && u[2] == 0xff && u[3] == 0x9b && (4 until 12).all { u[it] == 0 }) return embedded(12)
            // NAT64 local-use prefix 64:ff9b:1::/48
            if (u[0] == 0x00 && u[1] == 0x64 && u[2] == 0xff && u[3] == 0x9b && u[4] == 0x00 && u[5] == 0x01) return false
            // 6to4 2002:a.b.c.d::/48
            if (u[0] == 0x20 && u[1] == 0x02) return embedded(2)
            if (u[0] == 0x20 && u[1] == 0x01 && u[2] == 0x00 && u[3] == 0x00) return false // Teredo 2001::/32
            if (u[0] == 0x20 && u[1] == 0x01 && u[2] == 0x0d && u[3] == 0xb8) return false // documentation 2001:db8::/32
            if (u[0] == 0x01 && u[1] == 0x00 && (2 until 8).all { u[it] == 0 }) return false // discard 100::/64
        }
        return true
    }
}

