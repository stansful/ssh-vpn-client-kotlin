package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import kotlinx.coroutines.flow.Flow

interface VpnConnectionRepository {
    val state: Flow<VpnConnectionState>
    fun setConnecting(configId: String?)
    fun setConnected(configId: String)
    fun setDisconnecting(configId: String?)
    fun setDisconnected()
    fun setError(configId: String?, message: String)
    fun appendDiagnostic(message: String)
    fun clearDiagnostics()
}
