package com.stansful.sshvpnclient.data.local

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.model.defaultSessionOwner
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
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
    private val diagnosticsBuffer = BoundedDiagnosticsBuffer(
        maxEntries = MAX_DIAGNOSTIC_ENTRIES,
        maxCharacters = MAX_DIAGNOSTIC_CHARACTERS,
        maxEntryCharacters = MAX_DIAGNOSTIC_ENTRY_CHARACTERS,
    )
    private val mutableState = MutableStateFlow(VpnConnectionState())
    private val persistenceRequests = Channel<PersistenceRequest>(Channel.CONFLATED)
    private var diagnosticsTouched = false
    private var diagnosticsPublishJob: Job? = null
    private var lastDiagnosticsPersistElapsedMs = 0L

    override val state: Flow<VpnConnectionState> = mutableState.asStateFlow()
    override val currentState: VpnConnectionState
        get() = mutableState.value

    init {
        applicationScope.launch { processPersistenceRequests() }
        applicationScope.launch { restoreDiagnostics() }
    }

    override fun setConnecting(
        configId: String?,
        transport: VpnTransportType,
        sessionOwner: VpnSessionOwner?,
    ) {
        synchronized(stateLock) {
            diagnosticsTouched = true
            diagnosticsBuffer.clear()
            diagnosticsPublishJob?.cancel()
            mutableState.value = VpnConnectionState(
                status = VpnConnectionStatus.CONNECTING,
                activeConfigId = configId,
                activeTransport = transport,
                sessionOwner = sessionOwner ?: transport.defaultSessionOwner(),
            )
            enqueuePersistence(emptyList(), force = true)
        }
    }

    override fun setConnected(
        configId: String,
        transport: VpnTransportType,
        sessionOwner: VpnSessionOwner?,
    ) {
        updateState { state ->
            state.copy(
                status = VpnConnectionStatus.CONNECTED,
                activeConfigId = configId,
                errorMessage = null,
                activeTransport = transport,
                sessionOwner = sessionOwner ?: transport.defaultSessionOwner(),
            )
        }
    }

    override fun setReconnecting(
        configId: String,
        transport: VpnTransportType,
        sessionOwner: VpnSessionOwner?,
    ) {
        updateState { state ->
            state.copy(
                status = VpnConnectionStatus.RECONNECTING,
                activeConfigId = configId,
                errorMessage = null,
                activeTransport = transport,
                sessionOwner = sessionOwner ?: transport.defaultSessionOwner(),
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
                activeTransport = null,
                sessionOwner = null,
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
                activeTransport = null,
                sessionOwner = null,
            )
            enqueuePersistence(diagnostics, force = true)
        }
    }

    override fun appendDiagnostic(message: String) {
        val safeMessage = redactPersistentDestinationMetadata(message)
        val line = "${LocalTime.now().format(TIME_FORMAT)} $safeMessage"
        synchronized(stateLock) {
            diagnosticsTouched = true
            diagnosticsBuffer.addLast(line)
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
        val snapshot = diagnosticsBuffer.snapshot()
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
            mutableState.value = mutableState.value.copy(diagnostics = diagnosticsBuffer.snapshot())
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
            val bounded = BoundedDiagnosticsBuffer(
                maxEntries = MAX_DIAGNOSTIC_ENTRIES,
                maxCharacters = MAX_DIAGNOSTIC_CHARACTERS,
                maxEntryCharacters = MAX_DIAGNOSTIC_ENTRY_CHARACTERS,
            )
            for (index in 0 until array.length()) {
                val line = array.optString(index)
                if (line.isNotBlank()) {
                    bounded.addLast(redactPersistentDestinationMetadata(line))
                }
            }
            bounded.snapshot()
        }.getOrElse { emptyList() }
    }

    private data class PersistenceRequest(
        val diagnostics: List<String>,
        val force: Boolean,
    )

    private companion object {
        const val PREFERENCES_NAME = "ssh-vpn-connection-state"
        const val KEY_DIAGNOSTICS = "diagnostics"
        const val DIAGNOSTICS_UI_BATCH_MS = 250L
        const val DIAGNOSTICS_PERSIST_INTERVAL_MS = 15_000L
        const val MAX_DIAGNOSTIC_ENTRIES = 500
        const val MAX_DIAGNOSTIC_CHARACTERS = 128 * 1_024
        const val MAX_DIAGNOSTIC_ENTRY_CHARACTERS = 2 * 1_024
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

internal class BoundedDiagnosticsBuffer(
    private val maxEntries: Int,
    private val maxCharacters: Int,
    private val maxEntryCharacters: Int,
) {
    private val entries = ArrayDeque<String>(maxEntries)
    private var characterCount = 0

    init {
        require(maxEntries > 0)
        require(maxCharacters > 0)
        require(maxEntryCharacters in 1..maxCharacters)
    }

    fun addLast(value: String) {
        val boundedValue = if (value.length <= maxEntryCharacters) {
            value
        } else {
            value.take(maxEntryCharacters - 1) + "…"
        }
        while (
            entries.isNotEmpty() &&
            (entries.size >= maxEntries || characterCount + boundedValue.length > maxCharacters)
        ) {
            characterCount -= entries.removeFirst().length
        }
        entries.addLast(boundedValue)
        characterCount += boundedValue.length
    }

    fun addAll(values: Iterable<String>) {
        values.forEach(::addLast)
    }

    fun clear() {
        entries.clear()
        characterCount = 0
    }

    fun snapshot(): List<String> = entries.toList()
}

internal fun redactPersistentDestinationMetadata(message: String): String {
    val containsTunTcpMetadata = message.startsWith(TUN_TCP_DIAGNOSTIC_PREFIX) ||
        message.contains(" $TUN_TCP_DIAGNOSTIC_PREFIX")
    return if (containsTunTcpMetadata) {
        message.replace(IPV4_ENDPOINT_PATTERN, REDACTED_DESTINATION)
    } else {
        message
    }
}

private const val TUN_TCP_DIAGNOSTIC_PREFIX = "TUN TCP"
private const val REDACTED_DESTINATION = "<destination>"
private val IPV4_ENDPOINT_PATTERN = Regex(
    pattern = "(?<![0-9.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:[0-9]{1,5}(?![0-9])",
)
