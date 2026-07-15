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
    fun clearDiagnostics()
}
