package com.stansful.sshvpnclient.ui.opensource

import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import org.junit.Assert.assertFalse
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

    private fun activeXrayState(owner: VpnSessionOwner?) = VpnConnectionState(
        status = VpnConnectionStatus.CONNECTED,
        activeTransport = VpnTransportType.XRAY,
        sessionOwner = owner,
    )
}
