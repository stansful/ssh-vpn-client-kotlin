package com.stansful.sshvpnclient.data.local

import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        mutableState.value = mutableState.value.copy(
            status = VpnConnectionStatus.CONNECTED,
            activeConfigId = configId,
            errorMessage = null,
        )
    }

    override fun setDisconnecting(configId: String?) {
        mutableState.value = mutableState.value.copy(
            status = VpnConnectionStatus.DISCONNECTING,
            activeConfigId = configId,
            errorMessage = null,
        )
    }

    override fun setDisconnected() {
        mutableState.value = mutableState.value.copy(
            status = VpnConnectionStatus.DISCONNECTED,
            activeConfigId = null,
            errorMessage = null,
        )
    }

    override fun setError(configId: String?, message: String) {
        mutableState.value = mutableState.value.copy(
            status = VpnConnectionStatus.ERROR,
            activeConfigId = configId,
            errorMessage = message,
        )
    }

    override fun appendDiagnostic(message: String) {
        val line = "${timeFormat.format(Date())} $message"
        mutableState.value = mutableState.value.copy(
            diagnostics = (mutableState.value.diagnostics + line).takeLast(MAX_DIAGNOSTIC_LINES),
        )
    }

    override fun clearDiagnostics() {
        mutableState.value = mutableState.value.copy(diagnostics = emptyList())
    }

    private companion object {
        const val MAX_DIAGNOSTIC_LINES = 80
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
