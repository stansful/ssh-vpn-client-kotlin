package com.stansful.sshvpnclient.domain.usecase.vpn

import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwaitVpnOwnerReleaseTest {
    private val sshSession = VpnConnectionState(
        status = VpnConnectionStatus.CONNECTED,
        activeConfigId = "server-a",
        activeTransport = VpnTransportType.SSH,
        sessionOwner = VpnSessionOwner.SHADOW_SSH,
    )

    @Test
    fun `returns at once when the owner has already let go`() = runBlocking {
        val state = MutableStateFlow(VpnConnectionState())
        val startedAt = System.nanoTime()

        assertTrue(awaitVpnOwnerRelease(state, VpnSessionOwner.SHADOW_SSH, timeoutMs = 5_000L))
        assertTrue(System.nanoTime() - startedAt < 1_000_000_000L)
    }

    @Test
    fun `waits through a slow ssh teardown until the session is released`() = runBlocking {
        val state = MutableStateFlow(sshSession)
        launch {
            delay(150L)
            state.value = VpnConnectionState()
        }

        assertTrue(awaitVpnOwnerRelease(state, VpnSessionOwner.SHADOW_SSH, timeoutMs = 5_000L))
    }

    @Test
    fun `a hand over to another owner also ends the wait`() = runBlocking {
        val state = MutableStateFlow(sshSession)
        launch {
            delay(50L)
            state.value = VpnConnectionState(
                status = VpnConnectionStatus.CONNECTING,
                activeTransport = VpnTransportType.XRAY,
                sessionOwner = VpnSessionOwner.OPEN_SOURCE,
            )
        }

        assertTrue(awaitVpnOwnerRelease(state, VpnSessionOwner.SHADOW_SSH, timeoutMs = 5_000L))
    }

    @Test
    fun `gives up after the timeout while the owner keeps the session`() = runBlocking {
        val state = MutableStateFlow(sshSession)

        assertFalse(awaitVpnOwnerRelease(state, VpnSessionOwner.SHADOW_SSH, timeoutMs = 100L))
    }

    @Test
    fun `the ssh wait covers its teardown and is longer than an xray switch`() {
        val ssh = ConnectVpnUseCase.ownerReleaseTimeoutMs(VpnSessionOwner.SHADOW_SSH)
        // SshVpnService.disconnect: 1 s job join + up to 2 s forwarder stop, after its lifecycle lock.
        assertTrue(ssh >= 5_000L)
        assertTrue(ssh > ConnectVpnUseCase.ownerReleaseTimeoutMs(VpnSessionOwner.OPEN_SOURCE))
        assertTrue(ssh > ConnectVpnUseCase.ownerReleaseTimeoutMs(VpnSessionOwner.SMART_CONNECT))
    }
}
