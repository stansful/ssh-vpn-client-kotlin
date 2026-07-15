package com.stansful.sshvpnclient.vpn

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderlyingNetworkSelectionTest {
    @Test
    fun `underlying DNS endpoints support cellular IPv4 and IPv6`() {
        assertEquals("1.1.1.1:53", dnsEndpoint(InetAddress.getByName("1.1.1.1")))
        val ipv6Endpoint = dnsEndpoint(InetAddress.getByName("2001:4860:4860::8888"))
        assertTrue(ipv6Endpoint.startsWith("["))
        assertTrue(ipv6Endpoint.endsWith("]:53"))
    }

    @Test
    fun `only internet non-vpn candidates are eligible`() {
        val candidates = listOf(
            candidate("vpn", isNotVpn = false, isWifi = true),
            candidate("no-internet", hasInternet = false, isWifi = true),
        )

        assertNull(selectUnderlyingNetwork(candidates, activeKey = "vpn", currentKey = null))
    }

    @Test
    fun `unvalidated cellular is usable while carrier validation is delayed`() {
        val candidates = listOf(
            candidate("cellular", isValidated = false, isCellular = true),
        )

        assertEquals(
            "cellular",
            selectUnderlyingNetwork(candidates, activeKey = "cellular", currentKey = null),
        )
    }

    @Test
    fun `captured physical network stays sticky during active network oscillation`() {
        val candidates = listOf(
            candidate("wifi", isWifi = true, isNotMetered = true),
            candidate("cellular", isCellular = true),
        )

        assertEquals(
            "wifi",
            selectUnderlyingNetwork(candidates, activeKey = "cellular", currentKey = "wifi"),
        )
    }

    @Test
    fun `android active physical network wins initial selection`() {
        val candidates = listOf(
            candidate("wifi", isWifi = true, isNotMetered = true),
            candidate("cellular", isCellular = true),
        )

        assertEquals(
            "cellular",
            selectUnderlyingNetwork(candidates, activeKey = "cellular", currentKey = null),
        )
    }

    @Test
    fun `active cellular replaces captured wifi after wifi loses validation`() {
        val candidates = listOf(
            candidate("wifi", isValidated = false, isWifi = true, isNotMetered = true),
            candidate("cellular", isCellular = true),
        )

        assertEquals(
            "cellular",
            selectUnderlyingNetwork(candidates, activeKey = "cellular", currentKey = "wifi"),
        )
    }

    @Test
    fun `captured cellular stays selected after android active network becomes vpn`() {
        val candidates = listOf(
            candidate("cellular", isCellular = true),
            candidate("wifi", isWifi = true),
        )

        assertEquals(
            "cellular",
            selectUnderlyingNetwork(candidates, activeKey = null, currentKey = "cellular"),
        )
    }

    @Test
    fun `validated network wins initial selection over unvalidated fallback`() {
        val candidates = listOf(
            candidate("cellular", isValidated = false, isCellular = true),
            candidate("wifi", isValidated = true, isWifi = true),
        )

        assertEquals(
            "wifi",
            selectUnderlyingNetwork(candidates, activeKey = null, currentKey = null),
        )
    }

    @Test
    fun `current network breaks otherwise equal ties to avoid reconnect churn`() {
        val candidates = listOf(
            candidate("wifi-1", isWifi = true),
            candidate("wifi-2", isWifi = true),
        )

        assertEquals(
            "wifi-1",
            selectUnderlyingNetwork(candidates, activeKey = null, currentKey = "wifi-1"),
        )
    }

    private fun candidate(
        key: String,
        hasInternet: Boolean = true,
        isValidated: Boolean = true,
        isNotVpn: Boolean = true,
        isWifi: Boolean = false,
        isEthernet: Boolean = false,
        isCellular: Boolean = false,
        isNotMetered: Boolean = false,
    ) = UnderlyingNetworkCandidate(
        key = key,
        hasInternet = hasInternet,
        isValidated = isValidated,
        isNotVpn = isNotVpn,
        isWifi = isWifi,
        isEthernet = isEthernet,
        isCellular = isCellular,
        isNotMetered = isNotMetered,
    )
}
