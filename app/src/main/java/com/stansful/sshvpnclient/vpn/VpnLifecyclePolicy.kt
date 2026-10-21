package com.stansful.sshvpnclient.vpn

import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository

/**
 * Pure precondition for a service lifecycle mutation. The final Android-side guard is
 * Service.stopSelfResult(startId), which also observes starts queued by the system but not yet
 * delivered to onStartCommand().
 */
internal fun isVpnLifecycleCommandCurrent(
    expectedRunId: Long,
    currentRunId: Long,
    expectedCommandId: Long,
    currentCommandId: Long,
    expectedStartId: Int,
    serviceDestroyed: Boolean,
): Boolean {
    return expectedStartId > 0 &&
        !serviceDestroyed &&
        expectedRunId == currentRunId &&
        expectedCommandId == currentCommandId
}

internal fun isVpnSessionOwnedBy(
    state: VpnConnectionState,
    owner: VpnSessionOwner,
    transport: VpnTransportType,
): Boolean {
    return state.sessionOwner == owner && state.activeTransport == transport
}

/** Rejects a connect Intent that was queued before another tab became the desired owner. */
internal fun shouldAcceptVpnConnectCommand(
    state: VpnConnectionState,
    owner: VpnSessionOwner,
    transport: VpnTransportType,
    expectedConfigId: String? = null,
): Boolean {
    return isVpnSessionOwnedBy(state, owner, transport) &&
        state.status in CONNECT_COMMAND_STATUSES &&
        (expectedConfigId == null || state.activeConfigId == expectedConfigId)
}

/**
 * A service's own CONNECTING for a connect command it accepted ([shouldAcceptVpnConnectCommand]). The
 * use case that sent the command (the only sender: ConnectVpnUseCase, ConnectProxyVpnUseCase, also for
 * the quick tile and Always-on VPN) has published this session and cleared the log for it just before,
 * so the log is kept here. A second clear would drop what was written in between: a notice
 * ([VpnConnectionRepository.appendNotice], e.g. the secret store reset that the connect's own reads
 * found) and Always-on VPN's "started by Android" line.
 */
internal fun VpnConnectionRepository.publishAcceptedConnect(
    configId: String,
    transport: VpnTransportType,
    owner: VpnSessionOwner,
) {
    setConnecting(configId, transport, owner, clearDiagnostics = false)
}

/** A validation failure must not replace the state of an already running/starting owner. */
internal fun canPublishVpnStartFailure(state: VpnConnectionState): Boolean {
    return state.sessionOwner == null && state.activeTransport == null
}

/**
 * A switch may continue only after the snapshot owner has actually relinquished global state.
 * Proceeding merely because the same owner is still DISCONNECTING lets the new service race the
 * old runtime after the timeout and was the source of cross-tab lease invalidation.
 */
@Suppress("UNUSED_PARAMETER")
internal fun canProceedAfterVpnOwnerStop(
    stoppedOwner: VpnSessionOwner?,
    currentState: VpnConnectionState,
): Boolean {
    return currentState.sessionOwner == null
}

private val CONNECT_COMMAND_STATUSES = setOf(
    VpnConnectionStatus.CONNECTING,
    VpnConnectionStatus.RECONNECTING,
)
