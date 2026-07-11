package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnAddressFamilyPlanTest {
    @Test
    fun `SSH keeps its IPv4-only forwarding contract`() {
        val plans = vpnAddressFamilyPlans(VpnTunnelMode.SSH)

        assertEquals(1, plans.size)
        assertEquals("0.0.0.0", plans.single().defaultRoute)
        assertFalse(plans.single().address.contains(':'))
    }

    @Test
    fun `Xray captures IPv4 and IPv6 on mobile networks`() {
        val plans = vpnAddressFamilyPlans(VpnTunnelMode.XRAY)

        assertEquals(setOf("0.0.0.0", "::"), plans.map { it.defaultRoute }.toSet())
        assertTrue(plans.any { it.address.contains(':') && it.addressPrefix == 128 })
        assertTrue(plans.flatMap { it.dnsServers }.any { it.contains(':') })
    }
}
