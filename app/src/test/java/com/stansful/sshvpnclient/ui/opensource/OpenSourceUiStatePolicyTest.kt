package com.stansful.sshvpnclient.ui.opensource

import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourceUiStatePolicyTest {
    @Test
    fun `smart session occupies shared Xray runtime without posing as opensource connection`() {
        val state = OpenSourceUiState(
            unavailableUnpinnedCount = 3,
            vpnState = activeXrayState(VpnSessionOwner.SMART_CONNECT),
        )

        assertTrue(state.anyXrayRuntimeActive)
        assertFalse(state.xrayConnected)
        assertFalse(state.canRemoveUnavailable)
    }

    @Test
    fun `legacy ownerless Xray state still blocks runtime mutations`() {
        val state = OpenSourceUiState(
            unavailableUnpinnedCount = 1,
            vpnState = activeXrayState(owner = null),
        )

        assertTrue(state.anyXrayRuntimeActive)
        assertFalse(state.canRemoveUnavailable)
    }

    @Test
    fun `opensource owner remains the only source of xrayConnected`() {
        val state = OpenSourceUiState(
            vpnState = activeXrayState(VpnSessionOwner.OPEN_SOURCE),
        )

        assertTrue(state.anyXrayRuntimeActive)
        assertTrue(state.xrayConnected)
    }

    @Test
    fun `start failure published for the selected route is shown on the public tab`() {
        val state = OpenSourceUiState(
            profiles = listOf(selectedRoute("route")),
            vpnState = VpnConnectionState(
                status = VpnConnectionStatus.ERROR,
                activeConfigId = "route",
                errorMessage = "VPN permission required",
            ),
        )

        assertEquals("VPN permission required", state.openSourceErrorMessage)
    }

    @Test
    fun `another tab's error is not shown on the public tab`() {
        val sshError = VpnConnectionState(
            status = VpnConnectionStatus.ERROR,
            activeConfigId = "ssh-config",
            errorMessage = "Authentication failed",
        )
        val ownerlessError = sshError.copy(activeConfigId = null, errorMessage = "No apps selected")
        val routes = listOf(selectedRoute("route"))

        assertNull(OpenSourceUiState(profiles = routes, vpnState = sshError).openSourceErrorMessage)
        assertNull(OpenSourceUiState(profiles = routes, vpnState = ownerlessError).openSourceErrorMessage)
        assertNull(OpenSourceUiState(vpnState = ownerlessError).openSourceErrorMessage)
    }

    @Test
    fun `active route does not depend on the library's search or filter`() {
        val active = selectedRoute("route")
        val hidden = OpenSourceUiState(
            profiles = emptyList(),
            activeProfile = active,
            xrayCoreAvailable = true,
            vpnState = VpnConnectionState(
                status = VpnConnectionStatus.ERROR,
                activeConfigId = "route",
                errorMessage = "No apps selected",
            ),
        )

        assertEquals(active, hidden.selectedProfile)
        assertTrue(hidden.canStartOpenSource)
        assertEquals("No apps selected", hidden.openSourceErrorMessage)
    }

    private fun selectedRoute(id: String) = ProxyProfileSummary(
        id = id,
        name = "Route",
        protocol = ProxyProtocol.VLESS,
        host = "example.com",
        port = 443,
        transport = ProxyTransport.RAW,
        security = ProxySecurity.TLS,
        flow = null,
        fingerprint = "fingerprint-$id",
        source = ProxyProfileSource.REMOTE,
        isSelected = true,
        isPinned = false,
        isStale = false,
        lastTestStatus = ProxyTestStatus.NOT_TESTED,
        lastLatencyMs = null,
        updatedAt = 0L,
    )

    private fun activeXrayState(owner: VpnSessionOwner?) = VpnConnectionState(
        status = VpnConnectionStatus.CONNECTED,
        activeTransport = VpnTransportType.XRAY,
        sessionOwner = owner,
    )
}
