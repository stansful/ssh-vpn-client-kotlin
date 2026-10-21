package com.stansful.sshvpnclient.domain.usecase.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.stansful.sshvpnclient.data.local.LastVpnSessionStore
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.repository.SshPrivateKeyRepository
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.vpn.SshVpnService
import com.stansful.sshvpnclient.vpn.OpenSourceVpnService
import com.stansful.sshvpnclient.vpn.SmartConnectVpnService
import com.stansful.sshvpnclient.vpn.canProceedAfterVpnOwnerStop
import com.stansful.sshvpnclient.vpn.canPublishVpnStartFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** How a [ConnectVpnUseCase] call ended. */
sealed interface ConnectVpnResult {
    /** SshVpnService was asked to connect. */
    data object Started : ConnectVpnResult

    /**
     * A precondition failed (no server, no apps, missing key). [published]: [message] became the
     * connection error; false while another session owned that state, so only the caller can show it.
     */
    data class Rejected(val message: String, val published: Boolean) : ConnectVpnResult

    /**
     * Nothing to start: a Server session is already starting or running and nobody asked it to stop
     * (a second Connect tap, the quick tile), or another session took the VPN once the stopped
     * Server session let go.
     */
    data object Skipped : ConnectVpnResult

    /**
     * The session that owned the VPN before did not let go in time, so nothing was started. No error
     * is published: replacing the state of a still running owner would orphan its tunnel.
     */
    data object PreviousSessionActive : ConnectVpnResult
}

class ConnectVpnUseCase(
    private val context: Context,
    private val configRepository: SshConfigRepository,
    private val keyRepository: SshPrivateKeyRepository,
    private val vpnConnectionRepository: VpnConnectionRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val lastVpnSessionStore: LastVpnSessionStore,
) {
    /**
     * Starts a Server-mode session through the selected server.
     *
     * [afterSshStop]: the caller has just asked SshVpnService to stop the running Server session (a
     * restart through another server or after an app-routing change), so this first waits for that
     * session to let go. Without it a running Server session is left alone
     * ([ConnectVpnResult.Skipped]).
     */
    suspend operator fun invoke(
        preserveDiagnostics: Boolean = false,
        afterSshStop: Boolean = false,
    ): ConnectVpnResult {
        if (afterSshStop) {
            if (!awaitStoppedSshSession()) return ConnectVpnResult.PreviousSessionActive
            if (isTakenOver()) return ConnectVpnResult.Skipped
        }

        // Checked after that wait, so a failure is published into the state the stopped session left.
        val config = configRepository.getSelectedConfig()
            ?: return reject(null, "No configuration selected")

        val settings = appSettingsRepository.settings.value
        if (settings.vpnMode == VpnMode.SELECTED_APPS && settings.selectedAppPackages.isEmpty()) {
            return reject(config.id, "No apps selected")
        }

        if (config.authType == AuthType.PRIVATE_KEY) {
            val keyId = config.privateKeyId
            if (keyId.isNullOrBlank() || keyRepository.getById(keyId) == null) {
                return reject(config.id, "Selected SSH key not found")
            }
        }

        // Again after the reads above: the switch below would stop a session started meanwhile.
        if (afterSshStop && isTakenOver()) return ConnectVpnResult.Skipped
        val stateBeforeSwitch = vpnConnectionRepository.currentState
        if (stateBeforeSwitch.activeTransport == VpnTransportType.XRAY) {
            val disconnectIntent = if (
                stateBeforeSwitch.sessionOwner == VpnSessionOwner.SMART_CONNECT
            ) {
                SmartConnectVpnService.stopIntent(context.applicationContext)
            } else {
                OpenSourceVpnService.disconnectIntent(context.applicationContext)
            }
            context.applicationContext.startService(disconnectIntent)
            withTimeoutOrNull(TRANSPORT_SWITCH_TIMEOUT_MS) {
                vpnConnectionRepository.state.first { state ->
                    state.status == VpnConnectionStatus.DISCONNECTED ||
                        state.activeTransport != VpnTransportType.XRAY
                }
            }
        } else {
            context.stopService(Intent(context, OpenSourceVpnService::class.java))
            context.stopService(Intent(context, SmartConnectVpnService::class.java))
            when (val previousOwner = stateBeforeSwitch.sessionOwner) {
                null -> Unit
                // Starting or running, and nobody asked it to stop: waiting for it would only time out.
                VpnSessionOwner.SHADOW_SSH -> return ConnectVpnResult.Skipped
                // Stopped just above.
                VpnSessionOwner.OPEN_SOURCE,
                VpnSessionOwner.SMART_CONNECT,
                -> awaitVpnOwnerRelease(
                    state = vpnConnectionRepository.state,
                    owner = previousOwner,
                    timeoutMs = TRANSPORT_SWITCH_TIMEOUT_MS,
                )
            }
        }
        if (!canProceedAfterVpnOwnerStop(
                stoppedOwner = stateBeforeSwitch.sessionOwner,
                currentState = vpnConnectionRepository.currentState,
            )
        ) {
            return ConnectVpnResult.PreviousSessionActive
        }

        lastVpnSessionStore.record(VpnSessionOwner.SHADOW_SSH)
        if (preserveDiagnostics) {
            vpnConnectionRepository.setReconnecting(config.id)
        } else {
            vpnConnectionRepository.setConnecting(config.id)
        }
        try {
            ContextCompat.startForegroundService(
                context.applicationContext,
                SshVpnService.connectIntent(
                    context = context.applicationContext,
                    preserveDiagnostics = preserveDiagnostics,
                ),
            )
        } catch (error: RuntimeException) {
            // E.g. a foreground service start Android no longer allows. The session published above
            // is this call's own and no service will run it: release it here, where that is known,
            // so callers only ever publish failures into a state nobody owns.
            vpnConnectionRepository.setError(config.id, error.message ?: SERVICE_START_FAILED_MESSAGE)
            throw error
        }
        return ConnectVpnResult.Started
    }

    /**
     * Waits for the Server session the caller stopped to let go (SshVpnService: its lifecycle lock,
     * connection job join, TUN forwarder stop), then for Android to destroy the stopped service. False
     * when it still owns the VPN after [SSH_OWNER_RELEASE_TIMEOUT_MS].
     */
    private suspend fun awaitStoppedSshSession(): Boolean {
        val released = awaitVpnOwnerRelease(
            state = vpnConnectionRepository.state,
            owner = VpnSessionOwner.SHADOW_SSH,
            timeoutMs = SSH_OWNER_RELEASE_TIMEOUT_MS,
        )
        if (!released) return false
        // SshVpnService publishes the release before Android destroys it, and its onDestroy() clears any
        // SHADOW_SSH session it still finds: a start published right now would be wiped and its connect
        // command rejected as stale. Waited out after every such stop, also when the session had
        // already let go before this call (e.g. during a restart's pause).
        delay(SSH_SERVICE_DESTROY_GRACE_MS)
        return true
    }

    /**
     * After the stopped Server session let go: another mode or the quick tile has taken the VPN since,
     * and starting now would undo that.
     */
    private fun isTakenOver(): Boolean = vpnConnectionRepository.currentState.sessionOwner != null

    private fun reject(configId: String?, message: String): ConnectVpnResult.Rejected {
        val published = canPublishVpnStartFailure(vpnConnectionRepository.currentState)
        if (published) {
            vpnConnectionRepository.setError(configId, message)
        }
        return ConnectVpnResult.Rejected(message = message, published = published)
    }

    internal companion object {
        const val TRANSPORT_SWITCH_TIMEOUT_MS = 2_000L

        /**
         * SshVpnService's stop: waiting for its lifecycle lock (a pipeline rebuild may hold it through
         * a job join and a forwarder stop), then its own 1 s job join, up to 2 s TUN forwarder stop
         * and the SSH disconnect.
         */
        const val SSH_OWNER_RELEASE_TIMEOUT_MS = 8_000L

        /** Margin for Android to destroy the stopped SshVpnService (one IPC round trip). */
        const val SSH_SERVICE_DESTROY_GRACE_MS = 400L

        /** Same fallback as MainViewModel's, so both publish an identical error state. */
        const val SERVICE_START_FAILED_MESSAGE = "Unknown connection error"
    }
}

/**
 * Waits up to [timeoutMs] until [owner] no longer owns the global VPN session. True once it has let
 * go (to nobody or to another owner), false on timeout.
 */
internal suspend fun awaitVpnOwnerRelease(
    state: Flow<VpnConnectionState>,
    owner: VpnSessionOwner,
    timeoutMs: Long,
): Boolean {
    return withTimeoutOrNull(timeoutMs) {
        state.first { current -> current.sessionOwner != owner }
    } != null
}
