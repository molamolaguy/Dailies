package com.dailygames.hub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

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
}
