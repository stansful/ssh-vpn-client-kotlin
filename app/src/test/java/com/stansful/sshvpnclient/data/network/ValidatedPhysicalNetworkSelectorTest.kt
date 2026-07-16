package com.stansful.sshvpnclient.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ValidatedPhysicalNetworkSelectorTest {
    @Test
    fun `active VPN is ignored in favor of validated wifi`() {
        val candidates = listOf(
            candidate("vpn", hasNotVpnCapability = false, isVpnTransport = true),
            candidate("wifi", isWifi = true),
        )

        assertEquals("wifi", selectValidatedPhysicalNetwork(candidates, activeKey = "vpn"))
    }

    @Test
    fun `invalid wifi loses to validated cellular`() {
        val candidates = listOf(
            candidate("wifi", isValidated = false, isWifi = true),
            candidate("cellular", isCellular = true),
        )

        assertEquals("cellular", selectValidatedPhysicalNetwork(candidates, activeKey = "wifi"))
    }

    @Test
    fun `no physical network is selected when every candidate is invalid`() {
        val candidates = listOf(
            candidate("no-internet", hasInternet = false, isEthernet = true),
            candidate("unvalidated", isValidated = false, isWifi = true),
            candidate("vpn-capability", hasNotVpnCapability = false, isCellular = true),
            candidate("vpn-transport", isVpnTransport = true, isCellular = true),
        )

        assertNull(selectValidatedPhysicalNetwork(candidates, activeKey = "vpn-transport"))
    }

    @Test
    fun `active eligible physical network has priority over transport ranking`() {
        val candidates = listOf(
            candidate("ethernet", isEthernet = true),
            candidate("cellular", isCellular = true),
        )

        assertEquals(
            "cellular",
            selectValidatedPhysicalNetwork(candidates, activeKey = "cellular"),
        )
    }

    private fun candidate(
        key: String,
        hasInternet: Boolean = true,
        isValidated: Boolean = true,
        hasNotVpnCapability: Boolean = true,
        isVpnTransport: Boolean = false,
        isEthernet: Boolean = false,
        isWifi: Boolean = false,
        isCellular: Boolean = false,
    ) = ValidatedPhysicalNetworkCandidate(
        key = key,
        hasInternet = hasInternet,
        isValidated = isValidated,
        hasNotVpnCapability = hasNotVpnCapability,
        isVpnTransport = isVpnTransport,
        isEthernet = isEthernet,
        isWifi = isWifi,
        isCellular = isCellular,
    )
}
