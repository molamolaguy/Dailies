package com.dailygames.hub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

class SafeHttpTest {
    private fun addr(s: String) = InetAddress.getByName(s)

    @Test
    fun refusesLocalNetworkAddresses() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "169.254.169.254", "100.64.1.1", "0.0.0.0",
            "::1", "fd12:3456::1", "fe80::1", "::ffff:192.168.0.1").forEach {
            assertFalse(it, SafeHttp.isPublicAddress(addr(it)))
        }
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111").forEach { assertTrue(it, SafeHttp.isPublicAddress(addr(it))) }
    }

    @Test
    fun refusesLocalHostnamesAndBadSchemes() {
        val prev = SafeHttp.allowPrivateHostsForTests
        SafeHttp.allowPrivateHostsForTests = false
        try {
            assertFalse(SafeHttp.isPublicHost("localhost"))
            assertFalse(SafeHttp.isPublicHost("printer.local"))
            assertNull(SafeHttp.get("http://127.0.0.1:1/", maxBytes = 10))
            assertNull(SafeHttp.get("file:///etc/passwd", maxBytes = 10))
        } finally {
            SafeHttp.allowPrivateHostsForTests = prev
        }
    }

    @Test
    fun proxiedHostsRefuseLocalNamesAndEveryLocalAddressSpelling() {
        listOf("localhost", "printer.local", "localhost.", "127.0.0.1", "127.0.0.1.", "10.0.0.1", "[::1]", "::ffff:127.0.0.1",
            "127.1", "2130706433", "0x7f.1", "0x7f.0.0.1", "0177.0.0.1", "017700000001", "192.168.000.001", "").forEach {
            assertFalse(it, SafeHttp.proxiedHostAllowed(it))
        }
        listOf("8.8.8.8", "games.example", "[2606:4700:4700::1111]").forEach { assertTrue(it, SafeHttp.proxiedHostAllowed(it)) }
    }

    @Test
    fun usesTheSystemProxyWhenOneIsSet() {
        val seen = mutableListOf<String>()
        val proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seen += ex.requestURI.toString() + " host=" + ex.requestHeaders.getFirst("Host")
                val body = "<title>via proxy</title>".toByteArray()
                ex.responseHeaders.add("Content-Type", "text/html")
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            start()
        }
        val prev = SafeHttp.proxyForTests
        // Other test classes in this JVM switch local hosts on (WebsiteScraperTest): this test needs them refused.
        val prevPrivate = SafeHttp.allowPrivateHostsForTests
        SafeHttp.allowPrivateHostsForTests = false
        SafeHttp.proxyForTests = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.address.port))
        try {
            val r = SafeHttp.get("http://games.example/daily", maxBytes = 1000)
            assertNotNull(r)
            assertEquals(200, r!!.code)
            assertEquals("<title>via proxy</title>", String(r.body))
            assertEquals(listOf("http://games.example/daily host=games.example"), seen)
            // Local targets are still refused before anything reaches the proxy.
            assertNull(SafeHttp.get("http://192.168.1.1/admin", maxBytes = 1000))
            assertNull(SafeHttp.get("http://0x7f.1/", maxBytes = 1000))
            assertEquals(1, seen.size)
        } finally {
            SafeHttp.proxyForTests = prev
            SafeHttp.allowPrivateHostsForTests = prevPrivate
            proxy.stop(0)
        }
    }

    @Test
    fun additionalSpecialUseRangesAndLocalTldsAreRefused() {
        assertFalse(SafeHttp.isPublicAddress(addr("192.88.99.1")))
        assertFalse(SafeHttp.isPublicAddress(addr("64:ff9b:1::1")))
        assertFalse(SafeHttp.isPublicAddress(addr("::ffff:127.0.0.1")))

        val prevPrivate = SafeHttp.allowPrivateHostsForTests
        SafeHttp.allowPrivateHostsForTests = false
        try {
            assertNull(SafeHttp.resolvePublic("localhost."))
            assertNull(SafeHttp.resolvePublic("intranet.corp."))
            assertFalse(SafeHttp.proxiedHostAllowed("intranet"))
            assertFalse(SafeHttp.proxiedHostAllowed("router.lan"))
            assertFalse(SafeHttp.proxiedHostAllowed("nas.home"))
            assertFalse(SafeHttp.proxiedHostAllowed("box.corp"))
            assertFalse(SafeHttp.proxiedHostAllowed("1.168.192.in-addr.arpa"))
            assertFalse(SafeHttp.isAllowedForScrape("http://localhost./play"))
            assertFalse(SafeHttp.isAllowedForScrape("ftp://games.example/"))
        } finally {
            SafeHttp.allowPrivateHostsForTests = prevPrivate
        }
    }

    @Test
    fun redirectsStripSensitiveHeadersCrossOriginAndIgnore304() {
        val seenDestHeaders = mutableListOf<String?>()
        val dest = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/target") { ex ->
                seenDestHeaders += ex.requestHeaders.getFirst("Cookie")
                seenDestHeaders += ex.requestHeaders.getFirst("Authorization")
                val body = "ok".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        val src = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/redir") { ex ->
                ex.responseHeaders.add("Location", "http://127.0.0.1:${dest.address.port}/target?q=with space")
                ex.sendResponseHeaders(302, -1)
            }
            createContext("/same") { ex ->
                val body = "same".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            createContext("/same-redir") { ex ->
                ex.responseHeaders.add("Location", "/same")
                ex.sendResponseHeaders(302, -1)
            }
            createContext("/not-modified") { ex ->
                ex.responseHeaders.add("Location", "http://127.0.0.1:${dest.address.port}/target")
                ex.sendResponseHeaders(304, -1)
            }
            start()
        }
        val prevPrivate = SafeHttp.allowPrivateHostsForTests
        SafeHttp.allowPrivateHostsForTests = true
        try {
            val r = SafeHttp.get(
                "http://127.0.0.1:${src.address.port}/redir",
                headers = mapOf("Cookie" to "sid=secret", "Authorization" to "Bearer tok", "User-Agent" to "Dailies"),
                maxBytes = 1000
            )
            assertNotNull(r)
            assertEquals("ok", String(r!!.body))
            assertEquals(listOf<String?>(null, null), seenDestHeaders)

            val r304 = SafeHttp.get("http://127.0.0.1:${src.address.port}/not-modified", maxBytes = 1000)
            assertNotNull(r304)
            assertEquals(304, r304!!.code)
            assertEquals("http://127.0.0.1:${src.address.port}/not-modified", r304.finalUrl)
        } finally {
            SafeHttp.allowPrivateHostsForTests = prevPrivate
            src.stop(0)
            dest.stop(0)
        }
    }
}

