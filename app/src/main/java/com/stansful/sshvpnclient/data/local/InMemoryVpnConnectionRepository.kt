package com.stansful.sshvpnclient.data.local

import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryVpnConnectionRepository : VpnConnectionRepository {
    private val mutableState = MutableStateFlow(VpnConnectionState())

    override val state: Flow<VpnConnectionState> = mutableState.asStateFlow()

    override fun setConnecting(configId: String?) {
        mutableState.value = VpnConnectionState(
            status = VpnConnectionStatus.CONNECTING,
            activeConfigId = configId,
        )
    }

    override fun setConnected(configId: String) {
        mutableState.value = VpnConnectionState(
            status = VpnConnectionStatus.CONNECTED,
            activeConfigId = configId,
        )
    }

    override fun setDisconnecting(configId: String?) {
        mutableState.value = VpnConnectionState(
            status = VpnConnectionStatus.DISCONNECTING,
            activeConfigId = configId,
        )
    }

    override fun setDisconnected() {
        mutableState.value = VpnConnectionState(status = VpnConnectionStatus.DISCONNECTED)
    }

    override fun setError(configId: String?, message: String) {
        mutableState.value = VpnConnectionState(
            status = VpnConnectionStatus.ERROR,
            activeConfigId = configId,
            errorMessage = message,
        )
    }
}
