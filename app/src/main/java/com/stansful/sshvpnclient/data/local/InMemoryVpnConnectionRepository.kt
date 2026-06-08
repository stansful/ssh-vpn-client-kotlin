package com.stansful.sshvpnclient.data.local

import android.content.Context
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class InMemoryVpnConnectionRepository(
    context: Context,
) : VpnConnectionRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mutableState = MutableStateFlow(
        VpnConnectionState(diagnostics = readDiagnostics()),
    )

    override val state: Flow<VpnConnectionState> = mutableState.asStateFlow()

    override fun setConnecting(configId: String?) {
        persistDiagnostics(emptyList())
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

    override fun setReconnecting(configId: String) {
        mutableState.value = mutableState.value.copy(
            status = VpnConnectionStatus.RECONNECTING,
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
        val line = "${formattedCurrentTime()} $message"
        val diagnostics = mutableState.value.diagnostics + line
        persistDiagnostics(diagnostics)
        mutableState.value = mutableState.value.copy(
            diagnostics = diagnostics,
        )
    }

    override fun clearDiagnostics() {
        persistDiagnostics(emptyList())
        mutableState.value = mutableState.value.copy(diagnostics = emptyList())
    }

    private fun readDiagnostics(): List<String> {
        val raw = preferences.getString(KEY_DIAGNOSTICS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    add(array.optString(index))
                }
            }.filter { it.isNotBlank() }
        }.getOrElse {
            emptyList()
        }
    }

    private fun formattedCurrentTime(): String {
        return synchronized(timeFormat) {
            timeFormat.format(Date())
        }
    }

    private fun persistDiagnostics(diagnostics: List<String>) {
        val array = JSONArray()
        diagnostics.forEach { line -> array.put(line) }
        preferences.edit()
            .putString(KEY_DIAGNOSTICS, array.toString())
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "ssh-vpn-connection-state"
        const val KEY_DIAGNOSTICS = "diagnostics"
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
