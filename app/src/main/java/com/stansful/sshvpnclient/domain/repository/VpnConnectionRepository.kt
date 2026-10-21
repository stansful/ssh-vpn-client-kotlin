package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import kotlinx.coroutines.flow.Flow

interface VpnConnectionRepository {
    val state: Flow<VpnConnectionState>
    val currentState: VpnConnectionState
    fun setConnecting(
        configId: String?,
        transport: VpnTransportType = VpnTransportType.SSH,
        sessionOwner: VpnSessionOwner? = null,
        clearDiagnostics: Boolean = true,
    )
    fun setConnected(
        configId: String,
        transport: VpnTransportType = VpnTransportType.SSH,
        sessionOwner: VpnSessionOwner? = null,
    )
    fun setReconnecting(
        configId: String,
        transport: VpnTransportType = VpnTransportType.SSH,
        sessionOwner: VpnSessionOwner? = null,
    )
    fun setDisconnecting(configId: String?)
    fun setDisconnected()
    fun setError(configId: String?, message: String)
    fun appendDiagnostic(message: String)

    /**
     * A diagnostic the user must not miss. It also survives the next [setConnecting] that clears
     * the log, so it shows up in the log of the attempt it explains even when it was written just
     * before that attempt started; [clearDiagnostics] drops it.
     */
    fun appendNotice(message: String)
    fun clearDiagnostics()
}
