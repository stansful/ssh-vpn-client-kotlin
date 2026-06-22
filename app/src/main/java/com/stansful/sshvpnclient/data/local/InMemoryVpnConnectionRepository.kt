package com.stansful.sshvpnclient.data.local

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class InMemoryVpnConnectionRepository(
    context: Context,
    private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : VpnConnectionRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val stateLock = Any()
    private val diagnosticsBuffer = ArrayList<String>()
    private val mutableState = MutableStateFlow(VpnConnectionState())
    private val persistenceRequests = Channel<PersistenceRequest>(Channel.CONFLATED)
    private var diagnosticsTouched = false
    private var diagnosticsPublishJob: Job? = null
    private var lastDiagnosticsPersistElapsedMs = 0L

    override val state: Flow<VpnConnectionState> = mutableState.asStateFlow()

    init {
        applicationScope.launch { processPersistenceRequests() }
        applicationScope.launch { restoreDiagnostics() }
    }

    override fun setConnecting(configId: String?) {
        synchronized(stateLock) {
            diagnosticsTouched = true
            diagnosticsBuffer.clear()
            diagnosticsPublishJob?.cancel()
            mutableState.value = VpnConnectionState(
                status = VpnConnectionStatus.CONNECTING,
                activeConfigId = configId,
            )
            enqueuePersistence(emptyList(), force = true)
        }
    }

    override fun setConnected(configId: String) {
        updateState { state ->
            state.copy(
                status = VpnConnectionStatus.CONNECTED,
                activeConfigId = configId,
                errorMessage = null,
            )
        }
    }

    override fun setReconnecting(configId: String) {
        updateState { state ->
            state.copy(
                status = VpnConnectionStatus.RECONNECTING,
                activeConfigId = configId,
                errorMessage = null,
            )
        }
    }

    override fun setDisconnecting(configId: String?) {
        updateState { state ->
            state.copy(
                status = VpnConnectionStatus.DISCONNECTING,
                activeConfigId = configId,
                errorMessage = null,
            )
        }
    }

    override fun setDisconnected() {
        synchronized(stateLock) {
            val diagnostics = publishDiagnosticsLocked()
            mutableState.value = mutableState.value.copy(
                status = VpnConnectionStatus.DISCONNECTED,
                activeConfigId = null,
                errorMessage = null,
            )
            enqueuePersistence(diagnostics, force = true)
        }
    }

    override fun setError(configId: String?, message: String) {
        synchronized(stateLock) {
            val diagnostics = publishDiagnosticsLocked()
            mutableState.value = mutableState.value.copy(
                status = VpnConnectionStatus.ERROR,
                activeConfigId = configId,
                errorMessage = message,
            )
            enqueuePersistence(diagnostics, force = true)
        }
    }

    override fun appendDiagnostic(message: String) {
        val line = "${LocalTime.now().format(TIME_FORMAT)} $message"
        synchronized(stateLock) {
            diagnosticsTouched = true
            diagnosticsBuffer.add(line)
            if (diagnosticsPublishJob?.isActive != true) {
                diagnosticsPublishJob = applicationScope.launch {
                    delay(DIAGNOSTICS_UI_BATCH_MS)
                    synchronized(stateLock) {
                        val diagnostics = publishDiagnosticsLocked()
                        enqueuePersistence(diagnostics, force = false)
                    }
                }
            }
        }
    }

    override fun clearDiagnostics() {
        synchronized(stateLock) {
            diagnosticsTouched = true
            diagnosticsBuffer.clear()
            diagnosticsPublishJob?.cancel()
            mutableState.value = mutableState.value.copy(diagnostics = emptyList())
            enqueuePersistence(emptyList(), force = true)
        }
    }

    private fun updateState(transform: (VpnConnectionState) -> VpnConnectionState) {
        synchronized(stateLock) {
            mutableState.value = transform(mutableState.value)
        }
    }

    private fun publishDiagnosticsLocked(): List<String> {
        val snapshot = diagnosticsBuffer.toList()
        if (mutableState.value.diagnostics != snapshot) {
            mutableState.value = mutableState.value.copy(diagnostics = snapshot)
        }
        return snapshot
    }

    private suspend fun restoreDiagnostics() {
        val raw = withContext(ioDispatcher) {
            preferences.getString(KEY_DIAGNOSTICS, null)
        } ?: return
        val diagnostics = withContext(cpuDispatcher) { parseDiagnostics(raw) }
        synchronized(stateLock) {
            if (diagnosticsTouched) return
            diagnosticsBuffer.addAll(diagnostics)
            mutableState.value = mutableState.value.copy(diagnostics = diagnostics)
        }
    }

    private fun enqueuePersistence(diagnostics: List<String>, force: Boolean) {
        persistenceRequests.trySend(PersistenceRequest(diagnostics, force))
    }

    private suspend fun processPersistenceRequests() {
        for (request in persistenceRequests) {
            val now = SystemClock.elapsedRealtime()
            if (!request.force && now - lastDiagnosticsPersistElapsedMs < DIAGNOSTICS_PERSIST_INTERVAL_MS) {
                continue
            }
            val serialized = withContext(cpuDispatcher) {
                JSONArray(request.diagnostics).toString()
            }
            withContext(ioDispatcher) {
                preferences.edit {
                    putString(KEY_DIAGNOSTICS, serialized)
                }
            }
            lastDiagnosticsPersistElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    private fun parseDiagnostics(raw: String): List<String> {
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val line = array.optString(index)
                    if (line.isNotBlank()) add(line)
                }
            }
        }.getOrElse { emptyList() }
    }

    private data class PersistenceRequest(
        val diagnostics: List<String>,
        val force: Boolean,
    )

    private companion object {
        const val PREFERENCES_NAME = "ssh-vpn-connection-state"
        const val KEY_DIAGNOSTICS = "diagnostics"
        const val DIAGNOSTICS_UI_BATCH_MS = 100L
        const val DIAGNOSTICS_PERSIST_INTERVAL_MS = 15_000L
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
