package com.stansful.sshvpnclient.ui.opensource

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.AppUpdateCheckResult
import com.stansful.sshvpnclient.domain.model.AppUpdateDownloadState
import com.stansful.sshvpnclient.domain.model.AppUpdateInfo
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.model.XrayCoreAsset
import com.stansful.sshvpnclient.domain.model.XrayCoreRelease
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.AppUpdateDownloader
import com.stansful.sshvpnclient.domain.repository.AppUpdateRepository
import com.stansful.sshvpnclient.domain.repository.ProxyProfileRepository
import com.stansful.sshvpnclient.domain.repository.ProxySourceSynchronizer
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.domain.repository.XrayCoreUpdateRepository
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectProxyVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.DisconnectVpnUseCase
import com.stansful.sshvpnclient.xray.XrayCoreBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ProxyEditorState(
    val profileId: String? = null,
    val rawUri: String = "",
)

data class OpenSourceUiState(
    val profiles: List<ProxyProfileSummary> = emptyList(),
    val allProfileIds: Set<String> = emptySet(),
    val query: String = "",
    val pinnedOnly: Boolean = false,
    val protocolFilter: ProxyProtocol? = null,
    val selectedIds: Set<String> = emptySet(),
    val isSyncing: Boolean = false,
    val isChecking: Boolean = false,
    val checkCompleted: Int = 0,
    val checkTotal: Int = 0,
    val message: String? = null,
    val editor: ProxyEditorState? = null,
    val showBulkImport: Boolean = false,
    val showNoSelectedAppsDialog: Boolean = false,
    val appSettings: AppSettings = AppSettings(),
    val vpnState: VpnConnectionState = VpnConnectionState(),
    val xrayCoreAvailable: Boolean = false,
    val updateState: OpenSourceUpdateUiState = OpenSourceUpdateUiState(),
    val xrayCoreUpdateState: XrayCoreUpdateUiState = XrayCoreUpdateUiState(),
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
    val sshActive: Boolean
        get() = vpnState.activeTransport == VpnTransportType.SSH &&
            vpnState.status in setOf(
                VpnConnectionStatus.CONNECTING,
                VpnConnectionStatus.CONNECTED,
                VpnConnectionStatus.RECONNECTING,
            )
    val canStartOpenSource: Boolean
        get() = selectedProfile != null &&
            xrayCoreAvailable &&
            vpnState.status != VpnConnectionStatus.DISCONNECTING
}

data class OpenSourceUpdateUiState(
    val isChecking: Boolean = false,
    val availableUpdate: AppUpdateInfo? = null,
    val statusMessage: String? = null,
    val downloadState: AppUpdateDownloadState = AppUpdateDownloadState.Idle,
)

data class XrayCoreUpdateUiState(
    val runtimeAbi: String = "",
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadingAbi: String? = null,
    val release: XrayCoreRelease? = null,
    val statusMessage: String? = null,
)

class OpenSourceViewModel(
    private val proxyProfileRepository: ProxyProfileRepository,
    private val proxySourceSynchronizer: ProxySourceSynchronizer,
    private val xrayCoreBridge: XrayCoreBridge,
    private val appSettingsRepository: AppSettingsRepository,
    private val connectProxyVpnUseCase: ConnectProxyVpnUseCase,
    private val disconnectVpnUseCase: DisconnectVpnUseCase,
    private val vpnConnectionRepository: VpnConnectionRepository,
    private val appUpdateRepository: AppUpdateRepository,
    private val appUpdateDownloader: AppUpdateDownloader,
    private val xrayCoreUpdateRepository: XrayCoreUpdateRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val pinnedOnly = MutableStateFlow(false)
    private val protocolFilter = MutableStateFlow<ProxyProtocol?>(null)
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val operation = MutableStateFlow(OperationState())
    private val dialogState = MutableStateFlow(DialogState())
    private val showNoSelectedAppsDialog = MutableStateFlow(false)
    private val appUpdateState = MutableStateFlow(OpenSourceUpdateUiState())
    private val xrayCoreUpdateState = MutableStateFlow(
        XrayCoreUpdateUiState(runtimeAbi = xrayCoreUpdateRepository.runtimeAbi),
    )
    private var checkJob: Job? = null
    private var updateCheckJob: Job? = null
    private var xrayCoreDownloadJob: Job? = null
    private var settingsReconnectJob: Job? = null
    private var settingsReconnectStarted = false

    init {
        synchronize(force = false)
        viewModelScope.launch {
            var previousSplitTunnelSettings = appSettingsRepository.settings.value.splitTunnelSettings()
            appSettingsRepository.settings
                .drop(1)
                .collect { settings ->
                    val nextSplitTunnelSettings = settings.splitTunnelSettings()
                    if (previousSplitTunnelSettings != nextSplitTunnelSettings) {
                        applyVpnSettingsChange(settings)
                    }
                    previousSplitTunnelSettings = nextSplitTunnelSettings
                }
        }
        viewModelScope.launch {
            appUpdateDownloader.state.collect { downloadState ->
                appUpdateState.update { state ->
                    state.copy(
                        downloadState = downloadState,
                        statusMessage = when (downloadState) {
                            is AppUpdateDownloadState.Downloading -> {
                                val progress = downloadState.progressPercent?.let { " · $it%" }.orEmpty()
                                val paused = if (downloadState.isPaused) " · paused" else ""
                                "Downloading shadow-ssh ${downloadState.versionName}$progress$paused"
                            }
                            is AppUpdateDownloadState.Failed -> downloadState.message
                            is AppUpdateDownloadState.ReadyToInstall ->
                                "shadow-ssh ${downloadState.versionName} is ready to install"
                            AppUpdateDownloadState.Idle -> {
                                if (state.downloadState is AppUpdateDownloadState.Idle) {
                                    state.statusMessage
                                } else {
                                    null
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    private val profileListState = combine(
        proxyProfileRepository.observeSummaries(),
        query,
        pinnedOnly,
        protocolFilter,
        selectedIds,
    ) { profiles, queryValue, pinnedOnlyValue, filter, selected ->
        val filteredProfiles = profiles.filter { profile ->
            (filter == null || profile.protocol == filter) &&
                (!pinnedOnlyValue || profile.isPinned) &&
                (queryValue.isBlank() || profile.matches(queryValue))
        }
        ProfileListState(
            profiles = filteredProfiles,
            allProfileIds = profiles.mapTo(linkedSetOf(), ProxyProfileSummary::id),
            query = queryValue,
            pinnedOnly = pinnedOnlyValue,
            protocolFilter = filter,
            selectedIds = selected.intersect(profiles.mapTo(hashSetOf(), ProxyProfileSummary::id)),
        )
    }

    private val auxiliaryState = combine(
        operation,
        dialogState,
        vpnConnectionRepository.state,
        showNoSelectedAppsDialog,
    ) { operation, dialogs, vpnState, showNoSelectedApps ->
        AuxiliaryState(operation, dialogs, vpnState, showNoSelectedApps)
    }

    val uiState = combine(
        profileListState,
        auxiliaryState,
        appSettingsRepository.settings,
        appUpdateState,
        xrayCoreUpdateState,
    ) { profileState, auxiliary, appSettings, updateState, coreUpdateState ->
        val operation = auxiliary.operation
        val dialogs = auxiliary.dialogs
        OpenSourceUiState(
            profiles = profileState.profiles,
            allProfileIds = profileState.allProfileIds,
            query = profileState.query,
            pinnedOnly = profileState.pinnedOnly,
            protocolFilter = profileState.protocolFilter,
            selectedIds = profileState.selectedIds,
            isSyncing = operation.isSyncing,
            isChecking = operation.isChecking,
            checkCompleted = operation.checkCompleted,
            checkTotal = operation.checkTotal,
            message = operation.message,
            editor = dialogs.editor,
            showBulkImport = dialogs.showBulkImport,
            showNoSelectedAppsDialog = auxiliary.showNoSelectedAppsDialog,
            appSettings = appSettings,
            vpnState = auxiliary.vpnState,
            xrayCoreAvailable = xrayCoreBridge.isAvailable,
            updateState = updateState,
            xrayCoreUpdateState = coreUpdateState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OpenSourceUiState(xrayCoreAvailable = xrayCoreBridge.isAvailable),
    )

    fun setQuery(value: String) {
        query.value = value
    }

    fun setPinnedOnly(value: Boolean) {
        pinnedOnly.value = value
    }

    fun setProtocolFilter(value: ProxyProtocol?) {
        protocolFilter.value = value
    }

    fun synchronize(force: Boolean = true) {
        if (operation.value.isSyncing) return
        viewModelScope.launch {
            operation.update { it.copy(isSyncing = true, message = null) }
            runCatching { proxySourceSynchronizer.synchronize(force = force) }
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
        selectedIds.value = uiState.value.profiles
            .filterNot(ProxyProfileSummary::isPinned)
            .mapTo(linkedSetOf(), ProxyProfileSummary::id)
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

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch {
            proxyProfileRepository.setPinned(id, pinned)
        }
    }

    suspend fun rawUri(id: String): String = proxyProfileRepository.getById(id)?.rawUri.orEmpty()

    fun connect() {
        if (appSettingsRepository.settings.value.requiresSelectedAppsButHasNone()) {
            showNoSelectedAppsDialog.value = true
            return
        }
        viewModelScope.launch {
            runCatching {
                connectProxyVpnUseCase()
            }.onFailure { error ->
                vpnConnectionRepository.setError(
                    uiState.value.selectedProfile?.id,
                    error.message ?: "Unknown connection error",
                )
            }
        }
    }

    fun disconnect() {
        disconnectVpnUseCase()
    }

    fun dismissNoSelectedAppsDialog() {
        showNoSelectedAppsDialog.value = false
    }

    fun setShowLogsOnOpenSource(show: Boolean) {
        appSettingsRepository.setShowLogsOnOpenSource(show)
    }

    fun setShowOpenSourceWarningOnEnter(show: Boolean) {
        appSettingsRepository.setShowOpenSourceWarningOnEnter(show)
    }

    fun setOpenSourceRiskBannerExpanded(expanded: Boolean) {
        appSettingsRepository.setOpenSourceRiskBannerExpanded(expanded)
    }

    fun setOpenSourceAutoUpdateEnabled(enabled: Boolean) {
        appSettingsRepository.setOpenSourceAutoUpdateEnabled(enabled)
    }

    fun setThemeMode(themeMode: AppThemeMode) {
        appSettingsRepository.setThemeMode(themeMode)
    }

    fun setCustomThemeColors(colors: CustomThemeColors) {
        appSettingsRepository.setCustomThemeColors(colors)
    }

    fun setVpnMode(vpnMode: VpnMode) {
        appSettingsRepository.setVpnMode(vpnMode)
    }

    fun checkSelected() {
        val profileId = uiState.value.selectedProfile?.id ?: return
        runChecks(listOf(profileId))
    }

    fun checkAll() {
        runChecks(uiState.value.profiles.map(ProxyProfileSummary::id))
    }

    fun cancelChecks() {
        checkJob?.cancel()
        checkJob = null
        operation.update { it.copy(isChecking = false, message = "Tunnel checks cancelled") }
    }

    fun clearMessage() {
        operation.update { it.copy(message = null) }
    }

    fun checkForUpdates() {
        if (updateCheckJob?.isActive == true) return
        updateCheckJob = viewModelScope.launch {
            appUpdateState.update { it.copy(isChecking = true, statusMessage = null) }
            runCatching {
                appUpdateRepository.checkForUpdate(force = true)
            }.onSuccess { result ->
                appUpdateState.update { state ->
                    when (result) {
                        is AppUpdateCheckResult.Available -> state.copy(
                            isChecking = false,
                            availableUpdate = result.update,
                            statusMessage = null,
                        )
                        AppUpdateCheckResult.UpToDate -> state.copy(
                            isChecking = false,
                            availableUpdate = null,
                            statusMessage = "shadow-ssh is up to date",
                        )
                        AppUpdateCheckResult.NotDue -> state.copy(isChecking = false)
                    }
                }
            }.onFailure { error ->
                appUpdateState.update {
                    it.copy(
                        isChecking = false,
                        statusMessage = error.message ?: "Unable to check for updates",
                    )
                }
            }
        }
    }

    fun dismissAvailableUpdate() {
        appUpdateState.update { it.copy(availableUpdate = null) }
    }

    fun downloadAvailableUpdate() {
        val update = appUpdateState.value.availableUpdate ?: return
        appUpdateDownloader.download(update)
        appUpdateState.update { it.copy(availableUpdate = null, statusMessage = null) }
    }

    fun onUpdateActionFailed(message: String) {
        appUpdateState.update { it.copy(statusMessage = message) }
    }

    fun checkXrayCoreUpdates() {
        if (xrayCoreUpdateState.value.isChecking) return
        viewModelScope.launch {
            xrayCoreUpdateState.update { it.copy(isChecking = true, statusMessage = null) }
            runCatching {
                xrayCoreUpdateRepository.loadLatestRelease()
            }.onSuccess { release ->
                val runtimeAsset = release.assets.firstOrNull { asset -> asset.abi == release.runtimeAbi }
                xrayCoreUpdateState.update {
                    it.copy(
                        isChecking = false,
                        release = release,
                        statusMessage = when {
                            release.assets.isEmpty() ->
                                "No Xray core assets were published in ${release.versionName}"
                            runtimeAsset == null ->
                                "No Xray core asset for runtime ABI ${release.runtimeAbi}"
                            else ->
                                "Xray core ${release.versionName} is ready for ${release.runtimeAbi}"
                        },
                    )
                }
            }.onFailure { error ->
                xrayCoreUpdateState.update {
                    it.copy(
                        isChecking = false,
                        statusMessage = error.message ?: "Unable to check Xray core updates",
                    )
                }
            }
        }
    }

    fun downloadXrayCore(asset: XrayCoreAsset) {
        val state = xrayCoreUpdateState.value
        if (xrayCoreDownloadJob?.isActive == true || state.isDownloading || state.isChecking) return
        if (vpnConnectionRepository.currentState.isXrayActive()) {
            xrayCoreUpdateState.update {
                it.copy(statusMessage = "Disconnect opensource VPN before updating Xray core")
            }
            return
        }
        if (asset.abi != xrayCoreUpdateRepository.runtimeAbi) {
            xrayCoreUpdateState.update {
                it.copy(
                    statusMessage = "Xray core ${asset.abi} is not compatible with runtime ABI " +
                        xrayCoreUpdateRepository.runtimeAbi,
                )
            }
            return
        }

        xrayCoreDownloadJob = viewModelScope.launch {
            xrayCoreUpdateState.update {
                it.copy(
                    isDownloading = true,
                    downloadingAbi = asset.abi,
                    statusMessage = "Downloading Xray core for ${asset.abi}",
                )
            }
            var downloadedFile: java.io.File? = null
            runCatching {
                val file = xrayCoreUpdateRepository.download(asset)
                downloadedFile = file
                file.inputStream().use { input ->
                    xrayCoreBridge.installCore(input)
                }
            }.onSuccess {
                xrayCoreUpdateState.update {
                    it.copy(
                        isDownloading = false,
                        downloadingAbi = null,
                        statusMessage = "Xray core installed for ${asset.abi}",
                    )
                }
            }.onFailure { error ->
                xrayCoreUpdateState.update {
                    it.copy(
                        isDownloading = false,
                        downloadingAbi = null,
                        statusMessage = if (error is CancellationException) {
                            "Xray core download cancelled"
                        } else {
                            error.message ?: "Unable to install Xray core"
                        },
                    )
                }
            }.also {
                xrayCoreDownloadJob = null
            }
        }
    }

    fun cancelXrayCoreDownload() {
        xrayCoreDownloadJob?.cancel()
        xrayCoreDownloadJob = null
        xrayCoreUpdateState.update {
            it.copy(
                isDownloading = false,
                downloadingAbi = null,
                statusMessage = "Xray core download cancelled",
            )
        }
    }

    private fun importText(text: String, source: ProxyProfileSource) {
        viewModelScope.launch {
            val result = proxyProfileRepository.import(text, source)
            operation.update { it.copy(message = result.summary) }
        }
    }

    private fun applyVpnSettingsChange(settings: AppSettings) {
        if (settings.requiresSelectedAppsButHasNone()) {
            showNoSelectedAppsDialog.value = true
            return
        }
        if (settingsReconnectJob?.isActive == true) {
            if (settingsReconnectStarted) return
            settingsReconnectJob?.cancel()
        }
        if (!vpnConnectionRepository.currentState.isXrayActive()) return

        settingsReconnectJob = viewModelScope.launch {
            delay(SETTINGS_CHANGE_DEBOUNCE_MS)
            val latestSettings = appSettingsRepository.settings.value
            if (latestSettings.requiresSelectedAppsButHasNone()) {
                showNoSelectedAppsDialog.value = true
                return@launch
            }
            settingsReconnectStarted = true
            try {
                disconnectVpnUseCase()
                withTimeoutOrNull(TRANSPORT_SWITCH_TIMEOUT_MS) {
                    vpnConnectionRepository.state.first { state ->
                        state.status == VpnConnectionStatus.DISCONNECTED ||
                            state.activeTransport != VpnTransportType.XRAY
                    }
                }
                runCatching {
                    connectProxyVpnUseCase()
                }.onFailure { error ->
                    vpnConnectionRepository.setError(
                        uiState.value.selectedProfile?.id,
                        error.message ?: "Unknown connection error",
                    )
                }
            } finally {
                settingsReconnectStarted = false
            }
        }
    }

    private fun runChecks(profileIds: List<String>) {
        val distinctProfileIds = profileIds.distinct()
        if (checkJob?.isActive == true) return
        if (distinctProfileIds.isEmpty()) {
            operation.update { it.copy(message = "No configurations to check") }
            return
        }
        checkJob = viewModelScope.launch {
            var runningProfileId: String? = null
            var completedNormally = false
            try {
                val summariesById = uiState.value.profiles.associateBy(ProxyProfileSummary::id)
                operation.update {
                    it.copy(
                        isChecking = true,
                        checkCompleted = 0,
                        checkTotal = distinctProfileIds.size,
                        message = null,
                    )
                }
                var available = 0
                var unavailable = 0
                var unsupported = 0
                distinctProfileIds.forEachIndexed { index, profileId ->
                    runningProfileId = profileId
                    proxyProfileRepository.saveTestResult(
                        ProxyTunnelTestResult(profileId, ProxyTestStatus.RUNNING),
                    )
                    val summary = summariesById[profileId]
                    val profile = if (summary?.isStale == true ||
                        summary?.transport == ProxyTransport.UNKNOWN ||
                        summary?.security == ProxySecurity.UNKNOWN
                    ) {
                        null
                    } else {
                        proxyProfileRepository.getById(profileId)
                    }
                    val result = when {
                        summary?.isStale == true -> ProxyTunnelTestResult(
                            profileId,
                            ProxyTestStatus.UNAVAILABLE,
                            message = "Stale profile",
                        )
                        summary?.transport == ProxyTransport.UNKNOWN ||
                            summary?.security == ProxySecurity.UNKNOWN -> ProxyTunnelTestResult(
                                profileId,
                                ProxyTestStatus.UNSUPPORTED,
                                message = "Unsupported transport configuration",
                            )
                        profile == null -> ProxyTunnelTestResult(
                            profileId,
                            ProxyTestStatus.UNAVAILABLE,
                            message = "Missing profile",
                        )
                        else -> xrayCoreBridge.test(profile)
                    }
                    proxyProfileRepository.saveTestResult(result)
                    runningProfileId = null
                    when (result.status) {
                        ProxyTestStatus.AVAILABLE -> available += 1
                        ProxyTestStatus.UNSUPPORTED -> unsupported += 1
                        ProxyTestStatus.UNAVAILABLE -> unavailable += 1
                        ProxyTestStatus.RUNNING,
                        ProxyTestStatus.NOT_TESTED,
                        -> Unit
                    }
                    operation.update { it.copy(checkCompleted = index + 1) }
                }
                completedNormally = true
                operation.update {
                    it.copy(
                        isChecking = false,
                        message = "Tunnel check completed: " +
                            "$available available, $unavailable unavailable, $unsupported unsupported",
                    )
                }
            } finally {
                runningProfileId?.let { profileId ->
                    withContext(NonCancellable) {
                        proxyProfileRepository.saveTestResult(
                            ProxyTunnelTestResult(profileId, ProxyTestStatus.NOT_TESTED),
                        )
                    }
                }
                if (!completedNormally) {
                    operation.update { it.copy(isChecking = false) }
                }
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
    val pinnedOnly: Boolean,
    val protocolFilter: ProxyProtocol?,
    val selectedIds: Set<String>,
)

private data class AuxiliaryState(
    val operation: OperationState,
    val dialogs: DialogState,
    val vpnState: VpnConnectionState,
    val showNoSelectedAppsDialog: Boolean,
)

private fun ProxyProfileSummary.matches(query: String): Boolean {
    return listOf(name, host, protocol.name, transport.name, security.name)
        .any { value -> value.contains(query, ignoreCase = true) }
}

private fun AppSettings.requiresSelectedAppsButHasNone(): Boolean {
    return vpnMode == VpnMode.SELECTED_APPS && selectedAppPackages.isEmpty()
}

private fun AppSettings.splitTunnelSettings(): SplitTunnelSettings {
    return SplitTunnelSettings(
        vpnMode = vpnMode,
        selectedAppPackages = if (vpnMode == VpnMode.SELECTED_APPS) {
            selectedAppPackages
        } else {
            emptySet()
        },
    )
}

private fun VpnConnectionState.isXrayActive(): Boolean {
    return activeTransport == VpnTransportType.XRAY &&
        status in setOf(
            VpnConnectionStatus.CONNECTING,
            VpnConnectionStatus.CONNECTED,
            VpnConnectionStatus.RECONNECTING,
        )
}

private data class SplitTunnelSettings(
    val vpnMode: VpnMode,
    val selectedAppPackages: Set<String>,
)

private const val SETTINGS_CHANGE_DEBOUNCE_MS = 250L
private const val TRANSPORT_SWITCH_TIMEOUT_MS = 2_000L
