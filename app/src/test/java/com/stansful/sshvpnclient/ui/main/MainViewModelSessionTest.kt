package com.stansful.sshvpnclient.ui.main

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.stansful.sshvpnclient.data.local.LastVpnSessionStore
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.AppUpdateState
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.SshConfigSummary
import com.stansful.sshvpnclient.domain.model.SshPrivateKey
import com.stansful.sshvpnclient.domain.model.SshPrivateKeySummary
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.model.defaultSessionOwner
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.AppUpdateCoordinator
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.repository.SshPrivateKeyRepository
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.domain.usecase.config.SelectSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.DisconnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.ObserveVpnConnectionStateUseCase
import com.stansful.sshvpnclient.vpn.OpenSourceVpnService
import com.stansful.sshvpnclient.vpn.SmartConnectVpnService
import com.stansful.sshvpnclient.vpn.SshConnectionManager
import com.stansful.sshvpnclient.vpn.SshStopRequests
import com.stansful.sshvpnclient.vpn.SshVpnService
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Server-mode connect / restart orchestration of [MainViewModel] against the real use cases, with
 * fake repositories and the services only recorded (viewModelScope runs on the Robolectric main
 * looper, so time is advanced explicitly).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MainViewModelSessionTest {
    private lateinit var app: Application
    private lateinit var vpn: FakeVpnConnectionRepository
    private lateinit var configs: FakeSshConfigRepository
    private val store = ViewModelStore()
    private val startedServices = mutableListOf<Intent>()

    /** What SshVpnService records for every stop but a restart's own (notification, tile, Home). */
    private val stopRequests = SshStopRequests()

    /** The app container's scope, which restarts run in. */
    private val appScope = CoroutineScope(SupervisorJob())

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        vpn = FakeVpnConnectionRepository(SSH_SESSION_ON_A)
        configs = FakeSshConfigRepository(selected = config("server-b"))
    }

    @After
    fun tearDown() {
        store.clear()
        appScope.cancel()
    }

    @Test
    fun `restart waits for a slow ssh teardown and then reconnects through the new server`() {
        val viewModel = createViewModel()

        viewModel.reconnectThroughSelectedServer()
        idleFor(DEBOUNCE_AND_PAUSE_MS)
        assertTrue("the restart stops the old session", sshDisconnectRequested())

        // SshVpnService is still tearing down (lifecycle lock, job join, forwarder stop).
        idleFor(2_000L)
        assertEquals(SSH_SESSION_ON_A, vpn.currentState)
        assertTrue(sshConnectStarts().isEmpty())

        vpn.setDisconnected()
        idleFor(1_000L)

        assertEquals(VpnConnectionStatus.RECONNECTING, vpn.currentState.status)
        assertEquals("server-b", vpn.currentState.activeConfigId)
        assertEquals(VpnSessionOwner.SHADOW_SSH, vpn.currentState.sessionOwner)
        assertEquals(1, sshConnectStarts().size)
        assertTrue(vpn.errors.isEmpty())
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `disconnect during the restart debounce cancels the reconnect`() {
        val viewModel = createViewModel()

        viewModel.reconnectThroughSelectedServer()
        idleFor(100L)
        viewModel.disconnect()
        vpn.setDisconnected()
        idleFor(15_000L)

        assertEquals(VpnConnectionStatus.DISCONNECTED, vpn.currentState.status)
        assertTrue(sshConnectStarts().isEmpty())
        assertTrue(vpn.transitions.none { it.startsWith("reconnecting") || it.startsWith("connecting") })
    }

    @Test
    fun `disconnect while the restart waits for the old session cancels the reconnect`() {
        val viewModel = createViewModel()

        viewModel.reconnectThroughSelectedServer()
        idleFor(DEBOUNCE_AND_PAUSE_MS + 1_000L)
        viewModel.disconnect()
        vpn.setDisconnected()
        idleFor(15_000L)

        assertEquals(VpnConnectionStatus.DISCONNECTED, vpn.currentState.status)
        assertTrue(sshConnectStarts().isEmpty())
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `an old session that never lets go is reported without touching the global state`() {
        val viewModel = createViewModel()

        viewModel.reconnectThroughSelectedServer()
        idleFor(DEBOUNCE_AND_PAUSE_MS + 10_000L)

        // setError would clear SHADOW_SSH ownership and orphan the tunnel of a still running service.
        assertTrue(vpn.errors.isEmpty())
        assertEquals(SSH_SESSION_ON_A, vpn.currentState)
        assertTrue(sshConnectStarts().isEmpty())
        val notice = viewModel.notice.value
        assertNotNull(notice)
        assertEquals("Couldn’t reconnect", notice!!.message)
        assertEquals(MainViewModel.PREVIOUS_SESSION_ACTIVE_DETAIL, notice.detail)

        viewModel.consumeNotice(notice.id)
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `a restart is dropped when another mode takes the vpn over meanwhile`() {
        val viewModel = createViewModel()

        viewModel.reconnectThroughSelectedServer()
        idleFor(300L)
        vpn.setConnecting("route-1", VpnTransportType.XRAY, VpnSessionOwner.OPEN_SOURCE)
        idleFor(15_000L)

        assertEquals(VpnSessionOwner.OPEN_SOURCE, vpn.currentState.sessionOwner)
        assertTrue(sshConnectStarts().isEmpty())
        // The reconnect must not take the Xray path and stop the session the user just started.
        assertTrue(xrayServiceCommands().isEmpty())
        assertTrue(vpn.errors.isEmpty())
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `a stop from the notification while the restart waits for the old session drops the reconnect`() {
        val viewModel = createViewModel()

        viewModel.reconnectThroughSelectedServer()
        idleFor(DEBOUNCE_AND_PAUSE_MS + 1_000L)
        // The old session is still tearing down and its state still shows it connected.
        assertEquals(SSH_SESSION_ON_A, vpn.currentState)
        stopRequests.record()
        vpn.setDisconnected()
        idleFor(15_000L)

        assertEquals(VpnConnectionStatus.DISCONNECTED, vpn.currentState.status)
        assertTrue(sshConnectStarts().isEmpty())
        assertTrue(vpn.transitions.none { it.startsWith("reconnecting") || it.startsWith("connecting") })
        assertTrue(vpn.errors.isEmpty())
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `a stop from the quick tile before the restart's own stop drops the restart`() {
        val viewModel = createViewModel()

        viewModel.reconnectThroughSelectedServer()
        idleFor(100L)
        stopRequests.record()
        idleFor(15_000L)

        assertTrue(sshConnectStarts().isEmpty())
        assertTrue(vpn.transitions.isEmpty())
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `only the restart's own stop is marked as such for the service`() {
        val viewModel = createViewModel()

        viewModel.reconnectThroughSelectedServer()
        idleFor(MainViewModel.SETTINGS_CHANGE_DEBOUNCE_MS)
        assertTrue(SshVpnService.isRestartStop(sshIntents(SSH_ACTION_DISCONNECT).single()))

        viewModel.disconnect()
        idleFor(0L)
        val stops = sshIntents(SSH_ACTION_DISCONNECT)
        assertEquals(2, stops.size)
        assertFalse(SshVpnService.isRestartStop(stops.last()))
    }

    @Test
    fun `a cancelled restart publishes no connection error`() {
        val viewModel = createViewModel()
        viewModel.reconnectThroughSelectedServer()
        idleFor(DEBOUNCE_AND_PAUSE_MS)
        val gate = CompletableDeferred<Unit>()
        configs.gate = gate
        vpn.setDisconnected()
        idleFor(DESTROY_GRACE_MS + 100L)

        // The reconnect is suspended reading the server; Disconnect cancels it.
        viewModel.disconnect()
        gate.complete(Unit)
        idleFor(1_000L)

        assertTrue(vpn.errors.isEmpty())
        assertTrue(sshConnectStarts().isEmpty())
        assertTrue(vpn.transitions.none { it.startsWith("reconnecting") })
    }

    @Test
    fun `a restart that stopped the session reconnects after the activity is gone`() {
        createViewModel().reconnectThroughSelectedServer()
        idleFor(DEBOUNCE_AND_PAUSE_MS + 100L)
        assertTrue(sshDisconnectRequested())

        // The Activity finishes (swiped away from Recents) while the old session tears down.
        store.clear()
        vpn.setDisconnected()
        idleFor(1_000L)

        assertEquals(1, sshConnectStarts().size)
        assertEquals(VpnConnectionStatus.RECONNECTING, vpn.currentState.status)
        assertEquals("server-b", vpn.currentState.activeConfigId)
    }

    @Test
    fun `a restart still in its debounce ends with the activity`() {
        createViewModel().reconnectThroughSelectedServer()
        idleFor(100L)

        store.clear()
        idleFor(15_000L)

        assertTrue(startedServices.isEmpty())
        assertEquals(SSH_SESSION_ON_A, vpn.currentState)
    }

    @Test
    fun `a connect blocked by a routes session that does not stop is reported`() {
        vpn = FakeVpnConnectionRepository(ROUTES_SESSION)
        val viewModel = createViewModel()

        viewModel.connect()
        idleFor(ConnectVpnUseCase.TRANSPORT_SWITCH_TIMEOUT_MS + 500L)

        assertTrue(vpn.errors.isEmpty())
        assertEquals(ROUTES_SESSION, vpn.currentState)
        assertTrue(sshConnectStarts().isEmpty())
        assertEquals("Couldn’t connect", viewModel.notice.value?.message)
    }

    @Test
    fun `disconnect cancels a connect still waiting for the previous session`() {
        vpn = FakeVpnConnectionRepository(ROUTES_SESSION)
        val viewModel = createViewModel()

        viewModel.connect()
        idleFor(500L)
        viewModel.disconnect()
        vpn.setDisconnected()
        idleFor(5_000L)

        assertEquals(VpnConnectionStatus.DISCONNECTED, vpn.currentState.status)
        assertTrue(sshConnectStarts().isEmpty())
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `a plain connect from a stopped state starts the service at once`() {
        vpn = FakeVpnConnectionRepository(VpnConnectionState())
        val viewModel = createViewModel()

        viewModel.connect()
        idleFor(0L)

        assertEquals(VpnConnectionStatus.CONNECTING, vpn.currentState.status)
        assertEquals("server-b", vpn.currentState.activeConfigId)
        assertEquals(1, sshConnectStarts().size)
    }

    @Test
    fun `connect once the restarted session has stopped takes the restart over`() {
        val viewModel = createViewModel()

        viewModel.reconnectThroughSelectedServer()
        idleFor(DEBOUNCE_AND_PAUSE_MS)
        vpn.setDisconnected()
        // Home offers Connect now; the restart is still in its grace before it reconnects.
        idleFor(100L)
        viewModel.connect()
        // The connect takes over the restart's wait, grace included.
        idleFor(DESTROY_GRACE_MS - 1L)
        assertTrue(sshConnectStarts().isEmpty())
        idleFor(15_000L)

        assertEquals(1, sshConnectStarts().size)
        assertEquals(VpnConnectionStatus.CONNECTING, vpn.currentState.status)
        assertEquals("server-b", vpn.currentState.activeConfigId)
        assertTrue(vpn.transitions.none { it.startsWith("reconnecting") })
        assertTrue(vpn.errors.isEmpty())
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `a reconnect reads the server only once the old session let go`() {
        val viewModel = createViewModel()
        configs.failure = IllegalStateException("Server storage unavailable")

        viewModel.reconnectThroughSelectedServer()
        idleFor(DEBOUNCE_AND_PAUSE_MS + 2_000L)

        // Still waiting for the old session: nothing read, nothing published.
        assertTrue(vpn.errors.isEmpty())
        assertEquals(SSH_SESSION_ON_A, vpn.currentState)

        vpn.setDisconnected()
        idleFor(1_000L)

        // Read into the released state, so the failure is the connection error and needs no toast.
        assertEquals(listOf("Server storage unavailable"), vpn.errors)
        assertEquals(VpnConnectionStatus.ERROR, vpn.currentState.status)
        assertTrue(sshConnectStarts().isEmpty())
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `a reconnect that throws after the old session let go is published as the connection error`() {
        val viewModel = createViewModel()

        viewModel.reconnectThroughSelectedServer()
        idleFor(MainViewModel.SETTINGS_CHANGE_DEBOUNCE_MS)
        assertTrue(sshDisconnectRequested())
        vpn.setDisconnected()
        configs.failure = IllegalStateException("Server storage unavailable")
        idleFor(MainViewModel.SETTINGS_RECONNECT_DELAY_MS + DESTROY_GRACE_MS + 100L)

        assertEquals(listOf("Server storage unavailable"), vpn.errors)
        assertEquals(VpnConnectionStatus.ERROR, vpn.currentState.status)
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `a connect that throws leaves a running routes session alone`() {
        vpn = FakeVpnConnectionRepository(ROUTES_SESSION)
        val viewModel = createViewModel()
        configs.failure = IllegalStateException("Server storage unavailable")

        viewModel.connect()
        idleFor(0L)

        assertTrue(vpn.errors.isEmpty())
        assertEquals(ROUTES_SESSION, vpn.currentState)
        assertEquals("Couldn’t connect", viewModel.notice.value?.message)
        assertEquals("Server storage unavailable", viewModel.notice.value?.detail)
    }

    @Test
    fun `the reconnect waits until Android had time to destroy the stopped service`() {
        createViewModel().reconnectThroughSelectedServer()
        idleFor(DEBOUNCE_AND_PAUSE_MS + 1_000L)

        vpn.setDisconnected()
        // SshVpnService.onDestroy() would wipe a session published before it ran.
        idleFor(DESTROY_GRACE_MS - 1L)
        assertTrue(sshConnectStarts().isEmpty())

        idleFor(1L)
        assertEquals(1, sshConnectStarts().size)
        assertEquals(VpnConnectionStatus.RECONNECTING, vpn.currentState.status)
    }

    @Test
    fun `the destroy grace also applies when the old session let go during the restart's pause`() {
        createViewModel().reconnectThroughSelectedServer()
        idleFor(MainViewModel.SETTINGS_CHANGE_DEBOUNCE_MS)
        assertTrue(sshDisconnectRequested())
        idleFor(100L)
        vpn.setDisconnected()

        // The pause ends; the stopped service may still be waiting for its onDestroy().
        idleFor(MainViewModel.SETTINGS_RECONNECT_DELAY_MS - 100L)
        idleFor(DESTROY_GRACE_MS - 1L)
        assertTrue(sshConnectStarts().isEmpty())

        idleFor(1L)
        assertEquals(1, sshConnectStarts().size)
    }

    @Test
    fun `a second Connect tap during a takeover still waits out the destroy grace`() {
        val viewModel = createViewModel()
        viewModel.reconnectThroughSelectedServer()
        idleFor(DEBOUNCE_AND_PAUSE_MS)
        vpn.setDisconnected()
        idleFor(100L)
        viewModel.connect()
        idleFor(100L)

        viewModel.connect()
        idleFor(DESTROY_GRACE_MS - 1L)
        assertTrue(sshConnectStarts().isEmpty())

        idleFor(15_000L)
        assertEquals(1, sshConnectStarts().size)
        assertEquals(VpnConnectionStatus.CONNECTING, vpn.currentState.status)
    }

    @Test
    fun `a connect while the server session is already starting changes nothing`() {
        // E.g. a second tap before Home shows Connecting, or the quick tile started it meanwhile.
        val starting = VpnConnectionState(
            status = VpnConnectionStatus.CONNECTING,
            activeConfigId = "server-b",
            activeTransport = VpnTransportType.SSH,
            sessionOwner = VpnSessionOwner.SHADOW_SSH,
        )
        vpn = FakeVpnConnectionRepository(starting)
        val viewModel = createViewModel()

        viewModel.connect()
        idleFor(0L)

        assertEquals(starting, vpn.currentState)
        assertTrue(sshConnectStarts().isEmpty())
        idleFor(15_000L)
        assertTrue(vpn.errors.isEmpty())
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `a restart whose key was deleted publishes that error once the old session let go`() {
        configs.selected = config("server-b", privateKeyId = "deleted-key")
        val viewModel = createViewModel()

        viewModel.reconnectThroughSelectedServer()
        idleFor(DEBOUNCE_AND_PAUSE_MS + 1_000L)
        assertTrue(vpn.errors.isEmpty())

        vpn.setDisconnected()
        idleFor(1_000L)

        assertEquals(listOf(MISSING_KEY), vpn.errors)
        assertEquals(VpnConnectionStatus.ERROR, vpn.currentState.status)
        assertEquals("server-b", vpn.currentState.activeConfigId)
        assertTrue(sshConnectStarts().isEmpty())
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `a connect rejected while a routes session runs is shown without setError`() {
        vpn = FakeVpnConnectionRepository(ROUTES_SESSION)
        configs.selected = config("server-b", privateKeyId = "deleted-key")
        val viewModel = createViewModel()

        viewModel.connect()
        idleFor(0L)

        assertTrue(vpn.errors.isEmpty())
        assertEquals(ROUTES_SESSION, vpn.currentState)
        assertTrue(xrayServiceCommands().isEmpty())
        assertEquals("Couldn’t connect", viewModel.notice.value?.message)
        assertEquals(MISSING_KEY, viewModel.notice.value?.detail)
    }

    @Test
    fun `a service Android refuses to start releases the session the connect published`() {
        vpn = FakeVpnConnectionRepository(VpnConnectionState())
        val viewModel = createViewModel(context = ForegroundStartDeniedContext(app))

        viewModel.connect()
        idleFor(0L)

        assertEquals(VpnConnectionStatus.ERROR, vpn.currentState.status)
        assertEquals(FOREGROUND_START_DENIED, vpn.currentState.errorMessage)
        assertNull(vpn.currentState.sessionOwner)
        assertNull(vpn.currentState.activeTransport)
        assertEquals(FOREGROUND_START_DENIED, vpn.errors.last())
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `a restart whose service Android refuses to start does not stay reconnecting`() {
        val viewModel = createViewModel(context = ForegroundStartDeniedContext(app))

        viewModel.reconnectThroughSelectedServer()
        idleFor(DEBOUNCE_AND_PAUSE_MS)
        vpn.setDisconnected()
        idleFor(1_000L)

        assertEquals(VpnConnectionStatus.ERROR, vpn.currentState.status)
        assertEquals(FOREGROUND_START_DENIED, vpn.currentState.errorMessage)
        assertNull(vpn.currentState.sessionOwner)
        assertNull(viewModel.notice.value)
    }

    private fun createViewModel(context: Context = app): MainViewModel {
        val settings = FakeSettings()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(
                appSettingsRepository = settings,
                configRepository = configs,
                vpnConnectionRepository = vpn,
                connectVpnUseCase = ConnectVpnUseCase(
                    context = context,
                    configRepository = configs,
                    keyRepository = NoKeys,
                    vpnConnectionRepository = vpn,
                    appSettingsRepository = settings,
                    lastVpnSessionStore = LastVpnSessionStore(app),
                ),
                disconnectVpnUseCase = DisconnectVpnUseCase(app, vpn),
                selectSshConfigUseCase = SelectSshConfigUseCase(configs),
                sshConnectionManager = SshConnectionManager(),
                observeVpnConnectionStateUseCase = ObserveVpnConnectionStateUseCase(vpn),
                appUpdateCoordinator = NoUpdates,
                sshStopRequests = stopRequests,
                applicationScope = appScope,
            ) as T
        }
        val viewModel = ViewModelProvider(store, factory)[MainViewModel::class.java]
        idleFor(0L)
        return viewModel
    }

    private fun idleFor(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        shadowOf(Looper.getMainLooper()).idle()
        val shadowApp = shadowOf(app)
        while (true) {
            startedServices += shadowApp.nextStartedService ?: break
        }
    }

    private fun sshIntents(action: String): List<Intent> = startedServices.filter { intent ->
        intent.component?.className == SshVpnService::class.java.name && intent.action == action
    }

    private fun sshConnectStarts() = sshIntents(SSH_ACTION_CONNECT)

    private fun sshDisconnectRequested() = sshIntents(SSH_ACTION_DISCONNECT).isNotEmpty()

    /** Anything sent to the Routes / Auto services (e.g. their stop). */
    private fun xrayServiceCommands(): List<Intent> = startedServices.filter { intent ->
        intent.component?.className == OpenSourceVpnService::class.java.name ||
            intent.component?.className == SmartConnectVpnService::class.java.name
    }

    private class FakeVpnConnectionRepository(initial: VpnConnectionState) : VpnConnectionRepository {
        private val mutableState = MutableStateFlow(initial)
        val errors = mutableListOf<String>()
        val transitions = mutableListOf<String>()

        override val state: Flow<VpnConnectionState> = mutableState
        override val currentState: VpnConnectionState
            get() = mutableState.value

        override fun setConnecting(
            configId: String?,
            transport: VpnTransportType,
            sessionOwner: VpnSessionOwner?,
            clearDiagnostics: Boolean,
        ) {
            transitions += "connecting:$configId"
            mutableState.value = VpnConnectionState(
                status = VpnConnectionStatus.CONNECTING,
                activeConfigId = configId,
                activeTransport = transport,
                sessionOwner = sessionOwner ?: transport.defaultSessionOwner(),
            )
        }

        override fun setConnected(configId: String, transport: VpnTransportType, sessionOwner: VpnSessionOwner?) {
            mutableState.value = mutableState.value.copy(
                status = VpnConnectionStatus.CONNECTED,
                activeConfigId = configId,
                errorMessage = null,
                activeTransport = transport,
                sessionOwner = sessionOwner ?: transport.defaultSessionOwner(),
            )
        }

        override fun setReconnecting(configId: String, transport: VpnTransportType, sessionOwner: VpnSessionOwner?) {
            transitions += "reconnecting:$configId"
            mutableState.value = mutableState.value.copy(
                status = VpnConnectionStatus.RECONNECTING,
                activeConfigId = configId,
                errorMessage = null,
                activeTransport = transport,
                sessionOwner = sessionOwner ?: transport.defaultSessionOwner(),
            )
        }

        override fun setDisconnecting(configId: String?) {
            mutableState.value = mutableState.value.copy(
                status = VpnConnectionStatus.DISCONNECTING,
                activeConfigId = configId,
            )
        }

        override fun setDisconnected() {
            transitions += "disconnected"
            mutableState.value = VpnConnectionState()
        }

        override fun setError(configId: String?, message: String) {
            errors += message
            mutableState.value = mutableState.value.copy(
                status = VpnConnectionStatus.ERROR,
                activeConfigId = configId,
                errorMessage = message,
                activeTransport = null,
                sessionOwner = null,
            )
        }

        override fun appendDiagnostic(message: String) = Unit

        override fun clearDiagnostics() = Unit
    }

    /** Android refusing a foreground service start (e.g. ForegroundServiceStartNotAllowedException). */
    private class ForegroundStartDeniedContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun startForegroundService(service: Intent): ComponentName? {
            throw IllegalStateException(FOREGROUND_START_DENIED)
        }
    }

    private class FakeSshConfigRepository(var selected: SshConfig) : SshConfigRepository {
        /** When set, [getSelectedConfig] suspends until it completes. */
        var gate: CompletableDeferred<Unit>? = null

        /** When set, [getSelectedConfig] throws it (database / secret storage failure). */
        var failure: Exception? = null

        override suspend fun getSelectedConfig(): SshConfig {
            gate?.await()
            failure?.let { throw it }
            return selected
        }

        override fun observeSelectedSummary(): Flow<SshConfigSummary?> = flowOf(null)
        override fun observeSummaries(): Flow<List<SshConfigSummary>> = flowOf(emptyList())
        override suspend fun selectConfig(id: String) = Unit
        override fun observeAll(): Flow<List<SshConfig>> = flowOf(listOf(selected))
        override fun observeSelectedConfig(): Flow<SshConfig?> = flowOf(selected)
        override suspend fun getAll(): List<SshConfig> = listOf(selected)
        override suspend fun getById(id: String): SshConfig? = selected.takeIf { it.id == id }
        override suspend fun create(config: SshConfig) = error("unused")
        override suspend fun update(config: SshConfig) = error("unused")
        override suspend fun delete(id: String) = error("unused")
        override suspend fun getUsageCountForKey(keyId: String): Int = 0
    }

    private object NoKeys : SshPrivateKeyRepository {
        override fun observeAll(): Flow<List<SshPrivateKey>> = flowOf(emptyList())
        override fun observeSummaries(): Flow<List<SshPrivateKeySummary>> = flowOf(emptyList())
        override suspend fun getAll(): List<SshPrivateKey> = emptyList()
        override suspend fun getById(id: String): SshPrivateKey? = null
        override suspend fun create(key: SshPrivateKey) = error("unused")
        override suspend fun update(key: SshPrivateKey) = error("unused")
        override suspend fun delete(id: String) = error("unused")
        override suspend fun getUsageCount(keyId: String): Int = 0
    }

    private object NoUpdates : AppUpdateCoordinator {
        override val state: StateFlow<AppUpdateState> = MutableStateFlow(AppUpdateState())
        override val lastSuccessfulCheckAt: Flow<Long?> = flowOf(null)
        override fun checkForUpdates(manual: Boolean) = Unit
        override fun dismissAvailableUpdate() = Unit
        override fun downloadAvailableUpdate() = Unit
        override fun onActionFailed(message: String) = Unit
    }

    private class FakeSettings : AppSettingsRepository {
        override val settings: StateFlow<AppSettings> = MutableStateFlow(AppSettings(vpnMode = VpnMode.PROXY))

        override fun setShowLogsOnMain(show: Boolean) = Unit
        override fun setShowLogsOnOpenSource(show: Boolean) = Unit
        override fun setShowLogsOnSmartConnect(show: Boolean) = Unit
        override fun setShowConnectionActivity(show: Boolean) = Unit
        override fun setShowTerminalOnMain(show: Boolean) = Unit
        override fun setThemeMode(themeMode: AppThemeMode) = Unit
        override fun setCustomThemeColors(colors: CustomThemeColors) = Unit
        override fun setVpnMode(vpnMode: VpnMode) = Unit
        override fun setSelectedAppPackages(packageNames: Set<String>) = Unit
        override fun setActiveGlobalTab(tab: GlobalTab) = Unit
        override fun setOpenSourceConsentVersion(version: Int) = Unit
        override fun setShowOpenSourceWarningOnEnter(show: Boolean) = Unit
        override fun setOpenSourceRiskBannerExpanded(expanded: Boolean) = Unit
        override fun setOpenSourceAutoUpdateEnabled(enabled: Boolean) = Unit
        override fun setSmartConnectConsentVersion(version: Int) = Unit
        override fun setShowSmartConnectWarningOnEnter(show: Boolean) = Unit
        override fun setNotificationPermissionRequested(requested: Boolean) = Unit
    }

    private companion object {
        const val SSH_ACTION_CONNECT = "com.stansful.sshvpnclient.action.CONNECT"
        const val SSH_ACTION_DISCONNECT = "com.stansful.sshvpnclient.action.DISCONNECT"
        const val FOREGROUND_START_DENIED = "startForegroundService() not allowed"
        const val MISSING_KEY = "Selected SSH key not found"
        const val DESTROY_GRACE_MS = ConnectVpnUseCase.SSH_SERVICE_DESTROY_GRACE_MS

        /** The restart's debounce plus its pause before it reconnects. */
        const val DEBOUNCE_AND_PAUSE_MS =
            MainViewModel.SETTINGS_CHANGE_DEBOUNCE_MS + MainViewModel.SETTINGS_RECONNECT_DELAY_MS

        val SSH_SESSION_ON_A = VpnConnectionState(
            status = VpnConnectionStatus.CONNECTED,
            activeConfigId = "server-a",
            activeTransport = VpnTransportType.SSH,
            sessionOwner = VpnSessionOwner.SHADOW_SSH,
        )

        val ROUTES_SESSION = VpnConnectionState(
            status = VpnConnectionStatus.CONNECTED,
            activeConfigId = "route-1",
            activeTransport = VpnTransportType.XRAY,
            sessionOwner = VpnSessionOwner.OPEN_SOURCE,
        )

        /** A password server, or a key server when [privateKeyId] is given (no key exists in [NoKeys]). */
        fun config(id: String, privateKeyId: String? = null) = SshConfig(
            id = id,
            name = id,
            host = "$id.example",
            port = 22,
            username = "user",
            authType = if (privateKeyId == null) AuthType.PASSWORD else AuthType.PRIVATE_KEY,
            password = if (privateKeyId == null) "secret" else null,
            privateKeyId = privateKeyId,
            fingerprint = null,
            keepAliveIntervalSec = 30,
            enableUdpForwarding = false,
            note = null,
            createdAt = 0L,
            updatedAt = 0L,
        )
    }
}
