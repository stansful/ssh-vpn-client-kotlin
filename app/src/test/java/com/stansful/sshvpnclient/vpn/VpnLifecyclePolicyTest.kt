package com.stansful.sshvpnclient.vpn

import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnLifecyclePolicyTest {
    @Test
    fun `accepts only the current live service command`() {
        assertTrue(
            isVpnLifecycleCommandCurrent(
                expectedRunId = 7L,
                currentRunId = 7L,
                expectedCommandId = 11L,
                currentCommandId = 11L,
                expectedStartId = 3,
                serviceDestroyed = false,
            ),
        )
    }

    @Test
    fun `rejects superseded run or command`() {
        assertFalse(
            isVpnLifecycleCommandCurrent(
                expectedRunId = 7L,
                currentRunId = 8L,
                expectedCommandId = 11L,
                currentCommandId = 11L,
                expectedStartId = 3,
                serviceDestroyed = false,
            ),
        )
        assertFalse(
            isVpnLifecycleCommandCurrent(
                expectedRunId = 7L,
                currentRunId = 7L,
                expectedCommandId = 11L,
                currentCommandId = 12L,
                expectedStartId = 3,
                serviceDestroyed = false,
            ),
        )
    }

    @Test
    fun `rejects destroyed service and invalid start id`() {
        assertFalse(
            isVpnLifecycleCommandCurrent(
                expectedRunId = 7L,
                currentRunId = 7L,
                expectedCommandId = 11L,
                currentCommandId = 11L,
                expectedStartId = 3,
                serviceDestroyed = true,
            ),
        )
        assertFalse(
            isVpnLifecycleCommandCurrent(
                expectedRunId = 7L,
                currentRunId = 7L,
                expectedCommandId = 11L,
                currentCommandId = 11L,
                expectedStartId = 0,
                serviceDestroyed = false,
            ),
        )
    }

    @Test
    fun `connect command requires matching desired owner transport status and config`() {
        val state = VpnConnectionState(
            status = VpnConnectionStatus.CONNECTING,
            activeConfigId = "open-source-profile",
            activeTransport = VpnTransportType.XRAY,
            sessionOwner = VpnSessionOwner.OPEN_SOURCE,
        )

        assertTrue(
            shouldAcceptVpnConnectCommand(
                state = state,
                owner = VpnSessionOwner.OPEN_SOURCE,
                transport = VpnTransportType.XRAY,
                expectedConfigId = "open-source-profile",
            ),
        )
        assertFalse(
            shouldAcceptVpnConnectCommand(
                state = state.copy(sessionOwner = VpnSessionOwner.SMART_CONNECT),
                owner = VpnSessionOwner.OPEN_SOURCE,
                transport = VpnTransportType.XRAY,
                expectedConfigId = "open-source-profile",
            ),
        )
        assertFalse(
            shouldAcceptVpnConnectCommand(
                state = state.copy(status = VpnConnectionStatus.CONNECTED),
                owner = VpnSessionOwner.OPEN_SOURCE,
                transport = VpnTransportType.XRAY,
                expectedConfigId = "open-source-profile",
            ),
        )
        assertFalse(
            shouldAcceptVpnConnectCommand(
                state = state,
                owner = VpnSessionOwner.OPEN_SOURCE,
                transport = VpnTransportType.XRAY,
                expectedConfigId = "newer-profile",
            ),
        )
    }

    @Test
    fun `terminal ownership check distinguishes OpenSource from Smart on shared Xray transport`() {
        val smartState = VpnConnectionState(
            status = VpnConnectionStatus.CONNECTED,
            activeTransport = VpnTransportType.XRAY,
            sessionOwner = VpnSessionOwner.SMART_CONNECT,
        )

        assertFalse(
            isVpnSessionOwnedBy(
                smartState,
                VpnSessionOwner.OPEN_SOURCE,
                VpnTransportType.XRAY,
            ),
        )
        assertTrue(
            isVpnSessionOwnedBy(
                smartState,
                VpnSessionOwner.SMART_CONNECT,
                VpnTransportType.XRAY,
            ),
        )
    }

    @Test
    fun `suspended switch aborts when a different owner appears`() {
        val disconnected = VpnConnectionState()
        val oldOwnerStillStopping = VpnConnectionState(
            status = VpnConnectionStatus.DISCONNECTING,
            activeTransport = VpnTransportType.XRAY,
            sessionOwner = VpnSessionOwner.OPEN_SOURCE,
        )
        val newerSmartOwner = VpnConnectionState(
            status = VpnConnectionStatus.CONNECTING,
            activeTransport = VpnTransportType.XRAY,
            sessionOwner = VpnSessionOwner.SMART_CONNECT,
        )

        assertTrue(canProceedAfterVpnOwnerStop(VpnSessionOwner.OPEN_SOURCE, disconnected))
        assertFalse(canProceedAfterVpnOwnerStop(VpnSessionOwner.OPEN_SOURCE, oldOwnerStillStopping))
        assertFalse(canProceedAfterVpnOwnerStop(VpnSessionOwner.OPEN_SOURCE, newerSmartOwner))
    }

    @Test
    fun `start failure can publish only without an owned transport`() {
        assertTrue(canPublishVpnStartFailure(VpnConnectionState()))
        assertFalse(
            canPublishVpnStartFailure(
                VpnConnectionState(
                    status = VpnConnectionStatus.CONNECTED,
                    activeTransport = VpnTransportType.SSH,
                    sessionOwner = VpnSessionOwner.SHADOW_SSH,
                ),
            ),
        )
    }
}
