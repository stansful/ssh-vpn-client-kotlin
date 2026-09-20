package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelDnsPolicyTest {
    private val cloudflare = ipv4("1.1.1.1")
    private val google = ipv4("8.8.8.8")
    private val router = ipv4("192.168.3.1")

    @Test
    fun `the tunnel falls back to the resolvers the VPN interface gives apps`() {
        assertEquals(listOf(cloudflare, google), TunnelDnsPolicy.tunnelResolvers)
    }

    @Test
    fun `the resolver the app chose goes first, then the tunnel's`() {
        assertEquals(listOf(router, cloudflare, google), TunnelDnsPolicy.upstreamsFor(router))
        assertEquals(listOf(ipv4("9.9.9.9"), cloudflare, google), TunnelDnsPolicy.upstreamsFor(ipv4("9.9.9.9")))
        assertEquals(listOf(cloudflare, google), TunnelDnsPolicy.upstreamsFor(cloudflare))
        assertEquals(listOf(google, cloudflare), TunnelDnsPolicy.upstreamsFor(google))
    }

    @Test
    fun `a resolver that stayed silent is skipped until nothing else is left`() {
        assertEquals(listOf(cloudflare, google), TunnelDnsPolicy.upstreamsFor(router) { it == router })
        assertEquals(
            "1.1.1.1 is not answering from the server's network",
            listOf(router, google),
            TunnelDnsPolicy.upstreamsFor(router) { it == cloudflare },
        )
        assertEquals(listOf(router, cloudflare, google), TunnelDnsPolicy.upstreamsFor(router) { true })
    }

    @Test
    fun `a private resolver gets a short try, a public one half of the time left`() {
        assertEquals(1_500L, TunnelDnsPolicy.attemptBudgetMs(router, remainingMs = 10_000L))
        assertEquals(500L, TunnelDnsPolicy.attemptBudgetMs(router, remainingMs = 1_000L))
        assertEquals(5_000L, TunnelDnsPolicy.attemptBudgetMs(ipv4("9.9.9.9"), remainingMs = 10_000L))
    }

    @Test
    fun `private, carrier, loopback, link-local and multicast addresses are not public`() {
        listOf(
            "10.0.0.1",
            "100.64.0.1",
            "100.127.255.254",
            "127.0.0.53",
            "169.254.1.1",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.0.1",
            "0.0.0.0",
            "224.0.0.251",
            "255.255.255.255",
        ).forEach { address -> assertFalse(address, TunnelDnsPolicy.isPublicUnicast(ipv4(address))) }
    }

    @Test
    fun `private ranges end exactly where they should`() {
        listOf("172.15.255.255", "172.32.0.0", "100.63.255.255", "100.128.0.0", "11.0.0.1", "223.255.255.255")
            .forEach { address -> assertTrue(address, TunnelDnsPolicy.isPublicUnicast(ipv4(address))) }
    }

    private fun ipv4(text: String): Int {
        return text.split('.').map(String::toInt).fold(0) { address, octet -> (address shl 8) or octet }
    }
}
