package com.stansful.sshvpnclient.vpn

import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android's start as the receiving service hands it on. Unconfined stands in for the main thread: each
 * start runs until it suspends, and a check held open by a gate resumes when the test completes it.
 */
class AlwaysOnVpnStarterTest {
    private val repository = FakeVpnConnectionRepository()
    private val modeStarter = FakeModeStarter()
    private var lastOwner: VpnSessionOwner? = VpnSessionOwner.SHADOW_SSH
    private var finished = 0
    private val starter = AlwaysOnVpnStarter(
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        vpnConnectionRepository = repository,
        lastOwner = { lastOwner },
        modeStarter = modeStarter,
        mainDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `without a recorded mode the start only leaves a log line`() {
        lastOwner = null

        starter.start { finished += 1 }

        assertEquals(listOf(ALWAYS_ON_NO_RECORDED_MODE_DIAGNOSTIC), repository.diagnostics)
        assertEquals(0, modeStarter.checks)
        assertEquals(1, finished)
    }

    @Test
    fun `a running session is left alone`() {
        repository.currentState = live(VpnSessionOwner.SHADOW_SSH, VpnConnectionStatus.CONNECTED)

        starter.start { finished += 1 }

        assertEquals(
            listOf("Always-on VPN: Android's start ignored, Server is already connected"),
            repository.diagnostics,
        )
        assertEquals(0, modeStarter.checks)
        assertEquals(1, finished)
    }

    @Test
    fun `the recorded mode starts through its use case`() {
        lastOwner = VpnSessionOwner.OPEN_SOURCE
        modeStarter.preconditions = routesReady()
        modeStarter.onConnect = {
            repository.currentState = live(VpnSessionOwner.OPEN_SOURCE, VpnConnectionStatus.CONNECTING)
            null
        }

        starter.start { finished += 1 }

        assertEquals(listOf(VpnSessionOwner.OPEN_SOURCE to "route"), modeStarter.connects)
        assertEquals(listOf("Always-on VPN: Routes started by Android"), repository.diagnostics)
        assertEquals(1, finished)
    }

    @Test
    fun `a use case that gave up to another session is not logged as a start`() {
        modeStarter.onConnect = {
            // ConnectVpnUseCase waited on Room; Auto took the state meanwhile and the use case returned.
            repository.currentState = live(VpnSessionOwner.SMART_CONNECT, VpnConnectionStatus.CONNECTING)
            null
        }

        starter.start { finished += 1 }

        assertEquals(
            listOf("Always-on VPN: Server not started, Auto took over"),
            repository.diagnostics,
        )
    }

    @Test
    fun `a validation error the use case published is logged as the failure`() {
        modeStarter.onConnect = {
            repository.currentState = VpnConnectionState(
                status = VpnConnectionStatus.ERROR,
                activeConfigId = "server",
                errorMessage = "Selected SSH key not found",
            )
            null
        }

        starter.start { finished += 1 }

        assertEquals(
            listOf("Always-on VPN: Server failed to start: Selected SSH key not found"),
            repository.diagnostics,
        )
    }

    @Test
    fun `a use case that threw is logged with its message`() {
        modeStarter.onConnect = { "boom" }

        starter.start { finished += 1 }

        assertEquals(listOf("Always-on VPN: Server failed to start: boom"), repository.diagnostics)
        assertEquals(1, finished)
    }

    @Test
    fun `a missing prerequisite only says what to fix in the app`() {
        modeStarter.preconditions = serverReady().copy(configSelected = false)

        starter.start { finished += 1 }

        assertEquals(
            listOf("Always-on VPN: Server not started, no server selected; open the app to fix it"),
            repository.diagnostics,
        )
        assertTrue(modeStarter.connects.isEmpty())
        assertEquals(1, finished)
    }

    @Test
    fun `a session started while the checks ran is not started again`() {
        val gate = CompletableDeferred<Unit>()
        modeStarter.gate = gate

        starter.start { finished += 1 }
        repository.currentState = live(VpnSessionOwner.SMART_CONNECT, VpnConnectionStatus.CONNECTING)
        assertEquals(0, finished)
        gate.complete(Unit)

        assertEquals(
            listOf("Always-on VPN: Android's start ignored, Auto is already connecting"),
            repository.diagnostics,
        )
        assertTrue(modeStarter.connects.isEmpty())
        assertEquals(1, finished)
    }

    @Test
    fun `a second android start waits for the first instead of starting the mode twice`() {
        val gate = CompletableDeferred<Unit>()
        modeStarter.gate = gate
        modeStarter.onConnect = {
            repository.currentState = live(VpnSessionOwner.SHADOW_SSH, VpnConnectionStatus.CONNECTING)
            null
        }

        starter.start { finished += 1 }
        starter.start { finished += 1 }
        assertEquals(0, finished)
        gate.complete(Unit)

        assertEquals(1, modeStarter.checks)
        assertEquals(listOf(VpnSessionOwner.SHADOW_SSH to "server"), modeStarter.connects)
        assertEquals(listOf("Always-on VPN: Server started by Android"), repository.diagnostics)
        // Each receiving start is released, so each service can decide whether to stop for it.
        assertEquals(2, finished)
    }

    @Test
    fun `a failing lookup is logged and the start is still released`() {
        modeStarter.checkError = IllegalStateException("database closed")

        starter.start { finished += 1 }

        assertEquals(listOf("Always-on VPN: start failed: database closed"), repository.diagnostics)
        assertEquals(1, finished)
    }

    @Test
    fun `a refused notification does not stop the start`() {
        starter.enterForeground { throw IllegalStateException("not allowed") }

        assertEquals(
            listOf("Always-on VPN: could not show the notification: not allowed"),
            repository.diagnostics,
        )
    }

    private fun live(owner: VpnSessionOwner, status: VpnConnectionStatus) = VpnConnectionState(
        status = status,
        activeConfigId = "config",
        activeTransport = if (owner == VpnSessionOwner.SHADOW_SSH) VpnTransportType.SSH else VpnTransportType.XRAY,
        sessionOwner = owner,
    )

    private fun serverReady() = QuickTilePreconditions.ShadowSsh(
        configSelected = true,
        privateKeyReady = true,
        selectedAppsReady = true,
        vpnPermissionGranted = true,
    )

    private fun routesReady() = QuickTilePreconditions.OpenSource(
        consentAccepted = true,
        routeSelected = true,
        xrayCoreAvailable = true,
        selectedAppsReady = true,
        vpnPermissionGranted = true,
    )

    private inner class FakeModeStarter : VpnModeStarter {
        var preconditions: QuickTilePreconditions = serverReady()
        var gate: CompletableDeferred<Unit>? = null
        var checkError: Exception? = null
        var onConnect: (VpnSessionOwner) -> String? = { null }
        var checks = 0
        val connects = mutableListOf<Pair<VpnSessionOwner, String?>>()

        override suspend fun check(target: VpnSessionOwner): VpnModeStartCheck {
            checks += 1
            gate?.await()
            checkError?.let { throw it }
            val configId = if (target == VpnSessionOwner.OPEN_SOURCE) "route" else "server"
            return VpnModeStartCheck(preconditions, failureConfigId = configId)
        }

        override suspend fun connect(owner: VpnSessionOwner, failureConfigId: String?): String? {
            connects += owner to failureConfigId
            return onConnect(owner)
        }
    }
}

private class FakeVpnConnectionRepository : VpnConnectionRepository {
    private val mutableState = MutableStateFlow(VpnConnectionState())
    val diagnostics = mutableListOf<String>()

    override val state = mutableState
    override var currentState: VpnConnectionState
        get() = mutableState.value
        set(value) {
            mutableState.value = value
        }

    override fun setConnecting(
        configId: String?,
        transport: VpnTransportType,
        sessionOwner: VpnSessionOwner?,
        clearDiagnostics: Boolean,
    ) = Unit

    override fun setConnected(configId: String, transport: VpnTransportType, sessionOwner: VpnSessionOwner?) = Unit

    override fun setReconnecting(configId: String, transport: VpnTransportType, sessionOwner: VpnSessionOwner?) = Unit

    override fun setDisconnecting(configId: String?) = Unit

    override fun setDisconnected() = Unit

    override fun setError(configId: String?, message: String) = Unit

    override fun appendDiagnostic(message: String) {
        diagnostics += message
    }

    override fun appendNotice(message: String) {
        diagnostics += message
    }

    override fun clearDiagnostics() = Unit
}
