package com.stansful.sshvpnclient.ui.opensource

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.repository.ProxyProfileRepository
import com.stansful.sshvpnclient.domain.repository.ProxySourceSynchronizer
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectProxyVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.DisconnectVpnUseCase
import com.stansful.sshvpnclient.xray.XrayCoreBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProxyEditorState(
    val profileId: String? = null,
    val rawUri: String = "",
)

data class OpenSourceUiState(
    val profiles: List<ProxyProfileSummary> = emptyList(),
    val allProfileIds: Set<String> = emptySet(),
    val query: String = "",
    val protocolFilter: ProxyProtocol? = null,
    val selectedIds: Set<String> = emptySet(),
    val isSyncing: Boolean = false,
    val isChecking: Boolean = false,
    val checkCompleted: Int = 0,
    val checkTotal: Int = 0,
    val message: String? = null,
    val editor: ProxyEditorState? = null,
    val showBulkImport: Boolean = false,
    val vpnState: VpnConnectionState = VpnConnectionState(),
    val xrayCoreAvailable: Boolean = false,
) {
    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    val selectedProfile: ProxyProfileSummary? get() = profiles.firstOrNull(ProxyProfileSummary::isSelected)
    val xrayConnected: Boolean
        get() = vpnState.activeTransport == VpnTransportType.XRAY &&
            vpnState.status in setOf(
                VpnConnectionStatus.CONNECTING,
                VpnConnectionStatus.CONNECTED,
                VpnConnectionStatus.RECONNECTING,
            )
}

class OpenSourceViewModel(
    private val proxyProfileRepository: ProxyProfileRepository,
    private val proxySourceSynchronizer: ProxySourceSynchronizer,
    private val xrayCoreBridge: XrayCoreBridge,
    private val connectProxyVpnUseCase: ConnectProxyVpnUseCase,
    private val disconnectVpnUseCase: DisconnectVpnUseCase,
    vpnConnectionRepository: VpnConnectionRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val protocolFilter = MutableStateFlow<ProxyProtocol?>(null)
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val operation = MutableStateFlow(OperationState())
    private val dialogState = MutableStateFlow(DialogState())
    private var checkJob: Job? = null

    init {
        synchronize()
    }

    private val profileListState = combine(
        proxyProfileRepository.observeSummaries(),
        query,
        protocolFilter,
        selectedIds,
    ) { profiles, queryValue, filter, selected ->
        val filteredProfiles = profiles.filter { profile ->
            (filter == null || profile.protocol == filter) &&
                (queryValue.isBlank() || profile.matches(queryValue))
        }
        ProfileListState(
            profiles = filteredProfiles,
            allProfileIds = profiles.mapTo(linkedSetOf(), ProxyProfileSummary::id),
            query = queryValue,
            protocolFilter = filter,
            selectedIds = selected.intersect(profiles.mapTo(hashSetOf(), ProxyProfileSummary::id)),
        )
    }

    private val auxiliaryState = combine(
        operation,
        dialogState,
        vpnConnectionRepository.state,
    ) { operation, dialogs, vpnState -> AuxiliaryState(operation, dialogs, vpnState) }

    val uiState = combine(profileListState, auxiliaryState) { profileState, auxiliary ->
        val operation = auxiliary.operation
        val dialogs = auxiliary.dialogs
        OpenSourceUiState(
            profiles = profileState.profiles,
            allProfileIds = profileState.allProfileIds,
            query = profileState.query,
            protocolFilter = profileState.protocolFilter,
            selectedIds = profileState.selectedIds,
            isSyncing = operation.isSyncing,
            isChecking = operation.isChecking,
            checkCompleted = operation.checkCompleted,
            checkTotal = operation.checkTotal,
            message = operation.message,
            editor = dialogs.editor,
            showBulkImport = dialogs.showBulkImport,
            vpnState = auxiliary.vpnState,
            xrayCoreAvailable = xrayCoreBridge.isAvailable,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OpenSourceUiState(xrayCoreAvailable = xrayCoreBridge.isAvailable),
    )

    fun setQuery(value: String) {
        query.value = value
    }

    fun setProtocolFilter(value: ProxyProtocol?) {
        protocolFilter.value = value
    }

    fun synchronize() {
        if (operation.value.isSyncing) return
        viewModelScope.launch {
            operation.update { it.copy(isSyncing = true, message = null) }
            runCatching { proxySourceSynchronizer.synchronize() }
                .onSuccess { result ->
                    operation.update {
                        it.copy(
                            isSyncing = false,
                            message = if (result.notModified) {
                                "Public configurations are already up to date"
                            } else {
                                result.importResult.summary
                            },
                        )
                    }
                }
                .onFailure { error ->
                    operation.update {
                        it.copy(isSyncing = false, message = error.message ?: "Refresh failed")
                    }
                }
        }
    }

    fun showBulkImport() {
        dialogState.update { it.copy(showBulkImport = true) }
    }

    fun dismissBulkImport() {
        dialogState.update { it.copy(showBulkImport = false) }
    }

    fun importClipboard(text: String) {
        importText(text, ProxyProfileSource.CLIPBOARD)
        dismissBulkImport()
    }

    fun openEditor(profileId: String? = null) {
        if (profileId == null) {
            dialogState.update { it.copy(editor = ProxyEditorState()) }
            return
        }
        viewModelScope.launch {
            val raw = proxyProfileRepository.getById(profileId)?.rawUri.orEmpty()
            dialogState.update { it.copy(editor = ProxyEditorState(profileId, raw)) }
        }
    }

    fun updateEditor(value: String) {
        dialogState.update { state -> state.copy(editor = state.editor?.copy(rawUri = value)) }
    }

    fun dismissEditor() {
        dialogState.update { it.copy(editor = null) }
    }

    fun saveEditor() {
        val editor = dialogState.value.editor ?: return
        viewModelScope.launch {
            val result = if (editor.profileId == null) {
                proxyProfileRepository.import(editor.rawUri, ProxyProfileSource.MANUAL)
            } else {
                proxyProfileRepository.update(editor.profileId, editor.rawUri)
            }
            operation.update { it.copy(message = result.summary) }
            if (result.invalid == 0 && result.duplicates == 0) dismissEditor()
        }
    }

    fun selectProfile(id: String) {
        if (selectedIds.value.isNotEmpty()) {
            toggleBulkSelection(id)
            return
        }
        viewModelScope.launch { proxyProfileRepository.select(id) }
    }

    fun beginBulkSelection(id: String) {
        selectedIds.value = selectedIds.value + id
    }

    fun toggleBulkSelection(id: String) {
        selectedIds.update { selected -> if (id in selected) selected - id else selected + id }
    }

    fun selectAll() {
        selectedIds.value = uiState.value.allProfileIds
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            proxyProfileRepository.delete(setOf(id))
            operation.update { it.copy(message = "Configuration deleted") }
        }
    }

    fun deleteSelected() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            proxyProfileRepository.delete(ids)
            selectedIds.value = emptySet()
            operation.update { it.copy(message = "Deleted ${ids.size} configurations") }
        }
    }

    suspend fun rawUri(id: String): String = proxyProfileRepository.getById(id)?.rawUri.orEmpty()

    fun connect() {
        viewModelScope.launch { connectProxyVpnUseCase() }
    }

    fun disconnect() {
        disconnectVpnUseCase()
    }

    fun checkSelected() {
        val profileId = uiState.value.selectedProfile?.id ?: return
        runChecks(listOf(profileId))
    }

    fun checkAll() {
        runChecks(uiState.value.allProfileIds.toList())
    }

    fun cancelChecks() {
        checkJob?.cancel()
        checkJob = null
        operation.update { it.copy(isChecking = false, message = "Tunnel checks cancelled") }
    }

    fun clearMessage() {
        operation.update { it.copy(message = null) }
    }

    private fun importText(text: String, source: ProxyProfileSource) {
        viewModelScope.launch {
            val result = proxyProfileRepository.import(text, source)
            operation.update { it.copy(message = result.summary) }
        }
    }

    private fun runChecks(profileIds: List<String>) {
        if (profileIds.isEmpty() || checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            var runningProfileId: String? = null
            try {
                operation.update {
                    it.copy(isChecking = true, checkCompleted = 0, checkTotal = profileIds.size, message = null)
                }
                var available = 0
                profileIds.forEachIndexed { index, profileId ->
                    runningProfileId = profileId
                    proxyProfileRepository.saveTestResult(
                        ProxyTunnelTestResult(profileId, ProxyTestStatus.RUNNING),
                    )
                    val profile = proxyProfileRepository.getById(profileId)
                    val result = if (profile == null) {
                        ProxyTunnelTestResult(profileId, ProxyTestStatus.UNAVAILABLE, message = "Missing profile")
                    } else {
                        xrayCoreBridge.test(profile)
                    }
                    proxyProfileRepository.saveTestResult(result)
                    runningProfileId = null
                    if (result.status == ProxyTestStatus.AVAILABLE) available += 1
                    operation.update { it.copy(checkCompleted = index + 1) }
                }
                operation.update {
                    it.copy(message = "Tunnel check completed: $available/${profileIds.size} available")
                }
            } finally {
                runningProfileId?.let { profileId ->
                    withContext(NonCancellable) {
                        proxyProfileRepository.saveTestResult(
                            ProxyTunnelTestResult(profileId, ProxyTestStatus.NOT_TESTED),
                        )
                    }
                }
                operation.update { it.copy(isChecking = false) }
            }
        }
    }
}

private data class OperationState(
    val isSyncing: Boolean = false,
    val isChecking: Boolean = false,
    val checkCompleted: Int = 0,
    val checkTotal: Int = 0,
    val message: String? = null,
)

private data class DialogState(
    val editor: ProxyEditorState? = null,
    val showBulkImport: Boolean = false,
)

private data class ProfileListState(
    val profiles: List<ProxyProfileSummary>,
    val allProfileIds: Set<String>,
    val query: String,
    val protocolFilter: ProxyProtocol?,
    val selectedIds: Set<String>,
)

private data class AuxiliaryState(
    val operation: OperationState,
    val dialogs: DialogState,
    val vpnState: VpnConnectionState,
)

private fun ProxyProfileSummary.matches(query: String): Boolean {
    return listOf(name, host, protocol.name, transport.name, security.name)
        .any { value -> value.contains(query, ignoreCase = true) }
}
