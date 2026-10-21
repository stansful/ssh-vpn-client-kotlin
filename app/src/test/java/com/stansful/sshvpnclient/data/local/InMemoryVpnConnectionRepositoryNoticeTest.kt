package com.stansful.sshvpnclient.data.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.vpn.alwaysOnStartedDiagnostic
import com.stansful.sshvpnclient.vpn.publishAcceptedConnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A notice outlives the log clear that starts the next connection attempt, and only that one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class InMemoryVpnConnectionRepositoryNoticeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val repository = InMemoryVpnConnectionRepository(
        context = ApplicationProvider.getApplicationContext(),
        applicationScope = scope,
        ioDispatcher = Dispatchers.Unconfined,
        cpuDispatcher = Dispatchers.Unconfined,
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a notice written just before a connect stays in that attempt's log`() {
        repository.appendDiagnostic("Earlier session ended")
        // The connect's own config read is what finds the secret store unreadable.
        repository.appendNotice(NOTICE)

        repository.setConnecting(configId = CONFIG_ID)

        assertEquals(listOf(NOTICE), diagnostics())
    }

    @Test
    fun `the notice is carried into one attempt only`() {
        repository.appendNotice(NOTICE)
        repository.setConnecting(configId = CONFIG_ID)
        repository.appendDiagnostic("Authentication failed")
        repository.setError(CONFIG_ID, "Authentication failed")
        assertEquals(listOf(NOTICE, "Authentication failed"), diagnostics())

        repository.setConnecting(configId = CONFIG_ID)

        assertEquals(emptyList<String>(), diagnostics())
    }

    @Test
    fun `a connect that keeps the log leaves the notice for the next one that clears it`() {
        repository.appendNotice(NOTICE)
        repository.setConnecting(configId = CONFIG_ID, clearDiagnostics = false)

        repository.setConnecting(configId = CONFIG_ID)

        assertEquals(listOf(NOTICE), diagnostics())
    }

    @Test
    fun `a second clearing connect in a row drops the notice`() {
        // Why a service must not clear again after the use case that sent it its connect command.
        repository.appendNotice(NOTICE)
        repository.setConnecting(configId = CONFIG_ID)

        repository.setConnecting(configId = CONFIG_ID)

        assertEquals(emptyList<String>(), diagnostics())
    }

    @Test
    fun `a service's start publication keeps the notice and the lines written since the connect`() {
        repository.appendDiagnostic("Earlier session ended")
        // The connect's own config read found the secret store reset.
        repository.appendNotice(NOTICE)
        // ConnectVpnUseCase: the one clear of this start.
        repository.setConnecting(configId = CONFIG_ID)
        // AlwaysOnVpnStarter, once the use case returned.
        repository.appendDiagnostic(alwaysOnStartedDiagnostic(VpnSessionOwner.SHADOW_SSH))

        // SshVpnService accepts the command and publishes the start itself.
        repository.publishAcceptedConnect(CONFIG_ID, VpnTransportType.SSH, VpnSessionOwner.SHADOW_SSH)
        repository.appendDiagnostic("Starting VPN connection")
        assertEquals(VpnConnectionStatus.CONNECTING, repository.currentState.status)
        assertEquals(VpnSessionOwner.SHADOW_SSH, repository.currentState.sessionOwner)
        repository.setError(CONFIG_ID, "Authentication failed")

        assertEquals(
            listOf(NOTICE, "Always-on VPN: Server started by Android", "Starting VPN connection"),
            diagnostics(),
        )
    }

    @Test
    fun `a routes service's start publication keeps the log of its connect`() {
        repository.appendNotice(NOTICE)
        repository.setConnecting(ROUTE_ID, VpnTransportType.XRAY, VpnSessionOwner.OPEN_SOURCE)
        repository.appendDiagnostic(alwaysOnStartedDiagnostic(VpnSessionOwner.OPEN_SOURCE))

        repository.publishAcceptedConnect(ROUTE_ID, VpnTransportType.XRAY, VpnSessionOwner.OPEN_SOURCE)
        repository.setError(ROUTE_ID, "Xray core stopped unexpectedly")

        assertEquals(listOf(NOTICE, "Always-on VPN: Routes started by Android"), diagnostics())
    }

    @Test
    fun `clearing the log by hand drops the notice`() {
        repository.appendNotice(NOTICE)
        repository.clearDiagnostics()

        repository.setConnecting(configId = CONFIG_ID)

        assertEquals(emptyList<String>(), diagnostics())
    }

    @Test
    fun `ordinary lines are cleared by a connect as before`() {
        repository.appendDiagnostic("Earlier session ended")

        repository.setConnecting(configId = CONFIG_ID)

        assertEquals(emptyList<String>(), diagnostics())
    }

    /** Lines without their `HH:mm:ss` prefix. */
    private fun diagnostics(): List<String> {
        return repository.currentState.diagnostics.map { line -> line.substringAfter(' ') }
    }

    private companion object {
        const val CONFIG_ID = "config"
        const val ROUTE_ID = "route"
        const val NOTICE = "Saved passwords and keys can't be decrypted"
    }
}
