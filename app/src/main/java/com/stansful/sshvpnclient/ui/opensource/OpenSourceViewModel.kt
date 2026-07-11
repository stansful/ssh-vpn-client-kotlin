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
import com.stansful.sshvpnclient.xray.XrayCoreInstallResult
import com.stansful.sshvpnclient.xray.XrayRuntimeBusyException
import com.stansful.sshvpnclient.xray.XRAY_BATCH_TOTAL_BUDGET_MS
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ProxyEditorState(
    val profileId: String? = null,
    val rawUri: String = "",
)

enum class ProxyCheckPhase(val displayName: String) {
    PING_ENDPOINTS("Pinging endpoints"),
    TUNNELS("Checking tunnels"),
}

data class OpenSourceUiState(
    val profiles: List<ProxyProfileSummary> = emptyList(),
    val allProfileIds: Set<String> = emptySet(),
    val query: String = "",
    val pinnedOnly: Boolean = false,
    val protocolFilter: ProxyProtocol? = null,
    val selectedIds: Set<String> = emptySet(),
    val unavailableUnpinnedCount: Int = 0,
    val isSyncing: Boolean = false,
    val isRemovingUnavailable: Boolean = false,
    val isChecking: Boolean = false,
    val checkCompleted: Int = 0,
    val checkTotal: Int = 0,
    val checkPhase: ProxyCheckPhase? = null,
    val checkPhaseCompleted: Int = 0,
    val checkPhaseTotal: Int = 0,
    val hostPingMs: Map<String, Long> = emptyMap(),
    val message: String? = null,
    val editor: ProxyEditorState? = null,
    val showBulkImport: Boolean = false,
    val showRemoveUnavailableConfirmation: Boolean = false,
    val showNoSelectedAppsDialog: Boolean = false,
    val appSettings: AppSettings = AppSettings(),
    val vpnState: VpnConnectionState = VpnConnectionState(),
    val xrayCoreAvailable: Boolean = false,
    val updateState: OpenSourceUpdateUiState = OpenSourceUpdateUiState(),
    val xrayCoreUpdateState: XrayCoreUpdateUiState = XrayCoreUpdateUiState(),
) {
    val checkProgressText: String?
        get() {
            val phase = checkPhase ?: return null
            return "${phase.displayName} $checkPhaseCompleted/$checkPhaseTotal · " +
                "overall $checkCompleted/$checkTotal"
        }

    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    val canRemoveUnavailable: Boolean
        get() = unavailableUnpinnedCount > 0 &&
            !isSyncing &&
            !isChecking &&
            !isRemovingUnavailable &&
            !xrayConnected
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
            !isChecking &&
            !isRemovingUnavailable &&
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

@OptIn(FlowPreview::class)
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
    private val normalizedSearchQuery = query
        .debounce(PROFILE_SEARCH_DEBOUNCE_MS)
        .map { value -> value.trim() }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = "",
        )
    private val pinnedOnly = MutableStateFlow(false)
    private val protocolFilter = MutableStateFlow<ProxyProtocol?>(null)
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val operation = MutableStateFlow(OperationState())
    private val dialogState = MutableStateFlow(DialogState())
    private val showNoSelectedAppsDialog = MutableStateFlow(false)
    private val appUpdateState = MutableStateFlow(OpenSourceUpdateUiState())
    private val xrayCoreAvailable = MutableStateFlow(false)
    private val xrayCoreUpdateState = MutableStateFlow(
        XrayCoreUpdateUiState(runtimeAbi = xrayCoreUpdateRepository.runtimeAbi),
    )
    private var checkJob: Job? = null
    private var updateCheckJob: Job? = null
    private var xrayCoreDownloadJob: Job? = null
    private var settingsReconnectJob: Job? = null
    private var settingsReconnectStarted = false

    init {
        // Loading an installed runtime may touch disk and construct a DexClassLoader.
        viewModelScope.launch(Dispatchers.IO) {
            xrayCoreAvailable.value = xrayCoreBridge.isAvailable
        }
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

    private val filteredProfileListState = combine(
        proxyProfileRepository.observeSummaries(),
        normalizedSearchQuery,
        pinnedOnly,
        protocolFilter,
        selectedIds,
    ) { profiles, normalizedQuery, pinnedOnlyValue, filter, selected ->
        val filteredProfiles = if (filter == null && !pinnedOnlyValue && normalizedQuery.isBlank()) {
            profiles
        } else {
            profiles.filter { profile ->
                (filter == null || profile.protocol == filter) &&
                    (!pinnedOnlyValue || profile.isPinned) &&
                    (normalizedQuery.isBlank() || profile.matchesNormalized(normalizedQuery))
            }
        }
        val allProfileIds = profiles.mapTo(linkedSetOf(), ProxyProfileSummary::id)
        val unavailableUnpinnedCount = profiles.count { profile ->
            !profile.isPinned && profile.lastTestStatus == ProxyTestStatus.UNAVAILABLE
        }
        ProfileListState(
            profiles = filteredProfiles,
            allProfileIds = allProfileIds,
            query = normalizedQuery,
            pinnedOnly = pinnedOnlyValue,
            protocolFilter = filter,
            selectedIds = selected.filterTo(linkedSetOf()) { id -> id in allProfileIds },
            unavailableUnpinnedCount = unavailableUnpinnedCount,
        )
    }.flowOn(Dispatchers.Default)

    // Text input remains immediate while only the expensive list filtering is debounced.
    private val profileListState = combine(filteredProfileListState, query) { filtered, rawQuery ->
        filtered.copy(query = rawQuery)
    }

    private val auxiliaryState = combine(
        operation,
        dialogState,
        vpnConnectionRepository.state,
        showNoSelectedAppsDialog,
        xrayCoreAvailable,
    ) { operation, dialogs, vpnState, showNoSelectedApps, coreAvailable ->
        AuxiliaryState(operation, dialogs, vpnState, showNoSelectedApps, coreAvailable)
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
            unavailableUnpinnedCount = profileState.unavailableUnpinnedCount,
            isSyncing = operation.isSyncing,
            isRemovingUnavailable = operation.isRemovingUnavailable,
            isChecking = operation.isChecking,
            checkCompleted = operation.checkCompleted,
            checkTotal = operation.checkTotal,
            checkPhase = operation.checkPhase,
            checkPhaseCompleted = operation.checkPhaseCompleted,
            checkPhaseTotal = operation.checkPhaseTotal,
            hostPingMs = operation.hostPingMs,
            message = operation.message,
            editor = dialogs.editor,
            showBulkImport = dialogs.showBulkImport,
            showRemoveUnavailableConfirmation = dialogs.showRemoveUnavailableConfirmation,
            showNoSelectedAppsDialog = auxiliary.showNoSelectedAppsDialog,
            appSettings = appSettings,
            vpnState = auxiliary.vpnState,
            xrayCoreAvailable = auxiliary.xrayCoreAvailable,
            updateState = updateState,
            xrayCoreUpdateState = coreUpdateState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OpenSourceUiState(),
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
        if (operation.value.isSyncing || operation.value.isRemovingUnavailable) return
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

    fun requestRemoveUnavailable() {
        if (!canRemoveUnavailableNow()) return
        dialogState.update { it.copy(showRemoveUnavailableConfirmation = true) }
    }

    fun dismissRemoveUnavailableConfirmation() {
        dialogState.update { it.copy(showRemoveUnavailableConfirmation = false) }
    }

    fun removeUnavailableExceptPinned() {
        if (!canRemoveUnavailableNow()) {
            dismissRemoveUnavailableConfirmation()
            return
        }
        dismissRemoveUnavailableConfirmation()
        viewModelScope.launch {
            operation.update {
                it.copy(isRemovingUnavailable = true, message = null)
            }
            try {
                val removed = proxyProfileRepository.deleteUnavailableExceptPinned()
                operation.update {
                    it.copy(message = removedUnavailableMessage(removed))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                operation.update {
                    it.copy(
                        message = "Unable to remove unavailable tunnels: " +
                            (error.message ?: "unknown error"),
                    )
                }
            } finally {
                operation.update { it.copy(isRemovingUnavailable = false) }
            }
        }
    }

    private fun canRemoveUnavailableNow(): Boolean {
        return uiState.value.canRemoveUnavailable
    }

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch {
            proxyProfileRepository.setPinned(id, pinned)
        }
    }

    suspend fun rawUri(id: String): String = proxyProfileRepository.getById(id)?.rawUri.orEmpty()

    fun connect() {
        if (operation.value.isChecking || checkJob?.isCompleted == false) {
            operation.update { it.copy(message = "Cancel configuration checks before connecting") }
            return
        }
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
        runChecks(listOf(profileId), pingEndpoints = false)
    }

    fun checkAll() {
        // A full Xray batch probe supersedes the old duplicate TCP endpoint phase and keeps
        // hundreds of profiles within a short, bounded foreground operation. Use the complete
        // repository-backed ID set so an active search/filter cannot silently skip profiles.
        runChecks(uiState.value.allProfileIds.toList(), pingEndpoints = false)
    }

    fun cancelChecks() {
        val activeCheck = checkJob?.takeIf(Job::isActive) ?: return
        activeCheck.cancel()
        operation.update {
            it.copy(message = "Cancelling configuration checks")
        }
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
        if (vpnConnectionRepository.currentState.ownsXrayRuntime()) {
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
            var installResult: XrayCoreInstallResult? = null
            runCatching {
                val file = xrayCoreUpdateRepository.download(asset)
                downloadedFile = file
                file.inputStream().use { input ->
                    installResult = xrayCoreBridge.installCore(input)
                }
            }.onSuccess {
                xrayCoreAvailable.value = xrayCoreBridge.isAvailable
                xrayCoreUpdateState.update {
                    it.copy(
                        isDownloading = false,
                        downloadingAbi = null,
                        statusMessage = when (installResult) {
                            XrayCoreInstallResult.ALREADY_INSTALLED ->
                                "Xray core is already installed for ${asset.abi}"
                            XrayCoreInstallResult.INSTALLED_AFTER_RESTART ->
                                "Xray core updated for ${asset.abi}. Restart the app to use the new core."
                            XrayCoreInstallResult.INSTALLED,
                            null -> "Xray core installed for ${asset.abi}"
                        },
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

    private fun runChecks(profileIds: List<String>, pingEndpoints: Boolean) {
        val distinctProfileIds = profileIds.distinct()
        if (checkJob?.isCompleted == false) return
        if (vpnConnectionRepository.currentState.ownsXrayRuntime()) {
            operation.update {
                it.copy(message = "Disconnect opensource VPN before checking configurations")
            }
            return
        }
        if (distinctProfileIds.isEmpty()) {
            operation.update { it.copy(message = "No configurations to check") }
            return
        }
        lateinit var launchedJob: Job
        launchedJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var completedNormally = false
            val checksStartedAtNanos = System.nanoTime()
            val checksDeadlineNanos = checksStartedAtNanos +
                XRAY_BATCH_TOTAL_BUDGET_MS * NANOS_IN_MILLIS
            try {
                val summariesById = uiState.value.profiles.associateBy(ProxyProfileSummary::id)
                val totalWorkMultiplier = if (pingEndpoints) {
                    2
                } else {
                    1
                }
                val totalWork = distinctProfileIds.size * totalWorkMultiplier
                val endpointPingProfileCount = if (pingEndpoints) {
                    distinctProfileIds.count { profileId ->
                        summariesById[profileId]?.host?.isNumericIpLiteral() == true
                    }
                } else {
                    0
                }
                operation.update {
                    it.copy(
                        isChecking = true,
                        checkCompleted = 0,
                        checkTotal = totalWork,
                        checkPhase = if (pingEndpoints) {
                            ProxyCheckPhase.PING_ENDPOINTS
                        } else {
                            ProxyCheckPhase.TUNNELS
                        },
                        checkPhaseCompleted = 0,
                        checkPhaseTotal = distinctProfileIds.size,
                        hostPingMs = if (pingEndpoints) {
                            it.hostPingMs - distinctProfileIds.toSet()
                        } else {
                            it.hostPingMs
                        },
                        message = if (pingEndpoints) "Pinging endpoints" else null,
                    )
                }
                val endpointLatencies = if (pingEndpoints) {
                    pingProfileEndpoints(distinctProfileIds, summariesById)
                } else {
                    null
                }
                val pinged = endpointLatencies?.size ?: 0
                if (pingEndpoints) {
                    operation.update {
                        it.copy(
                            checkCompleted = distinctProfileIds.size,
                            checkPhase = ProxyCheckPhase.TUNNELS,
                            checkPhaseCompleted = 0,
                            checkPhaseTotal = distinctProfileIds.size,
                            message = "Checking tunnels",
                        )
                    }
                }
                val tunnelResults = checkProfileTunnels(
                    profileIds = distinctProfileIds,
                    summariesById = summariesById,
                    overallOffset = if (pingEndpoints) distinctProfileIds.size else 0,
                    endpointLatencies = endpointLatencies,
                    deadlineNanos = checksDeadlineNanos,
                )
                val available = tunnelResults.count { it.status == ProxyTestStatus.AVAILABLE }
                val unavailable = tunnelResults.count { it.status == ProxyTestStatus.UNAVAILABLE }
                val unsupported = tunnelResults.count { it.status == ProxyTestStatus.UNSUPPORTED }
                val notTested = tunnelResults.count { it.status == ProxyTestStatus.NOT_TESTED }
                val tunnelSummary = "$available available, $unavailable unavailable, " +
                    "$unsupported unsupported" +
                    if (notTested > 0) ", $notTested not tested before deadline" else ""
                val elapsedMs = (System.nanoTime() - checksStartedAtNanos) / NANOS_IN_MILLIS
                completedNormally = true
                operation.update {
                    it.copy(
                        isChecking = false,
                        checkCompleted = totalWork,
                        checkPhase = null,
                        checkPhaseCompleted = 0,
                        checkPhaseTotal = 0,
                        message = if (pingEndpoints) {
                            "Endpoint ping completed: $pinged/$endpointPingProfileCount numeric-IP profiles; " +
                                "tunnel check completed: $tunnelSummary"
                        } else {
                            "Tunnel check completed in ${elapsedMs}ms: $tunnelSummary"
                        },
                    )
                }
            } catch (error: XrayRuntimeBusyException) {
                operation.update { state ->
                    state.copy(
                        message = "${error.message}; checks stopped at " +
                            "${state.checkCompleted}/${state.checkTotal}",
                    )
                }
            } catch (error: CancellationException) {
                operation.update { state ->
                    state.copy(
                        message = "Configuration checks cancelled at " +
                            "${state.checkCompleted}/${state.checkTotal}",
                    )
                }
                throw error
            } catch (error: Throwable) {
                operation.update {
                    it.copy(message = error.message ?: "Configuration check failed")
                }
            } finally {
                if (!completedNormally) {
                    operation.update {
                        it.copy(
                            isChecking = false,
                            checkPhase = null,
                            checkPhaseCompleted = 0,
                            checkPhaseTotal = 0,
                        )
                    }
                }
                if (checkJob === launchedJob) {
                    checkJob = null
                }
            }
        }
        checkJob = launchedJob
        launchedJob.start()
    }

    private suspend fun pingProfileEndpoints(
        profileIds: List<String>,
        summariesById: Map<String, ProxyProfileSummary>,
    ): Map<String, Long> {
        val completedPings = AtomicInteger(0)
        val successfulLatencies = ConcurrentHashMap<String, Long>()
        val publicationGate = CheckProgressPublicationGate(profileIds.size)
        val pingGroups = groupEndpointPings(profileIds, summariesById)
        try {
            mapConcurrentOrdered(
                values = pingGroups,
                maxConcurrency = HOST_PING_CONCURRENCY,
                onResult = { _, result ->
                    val latencyMs = result.latencyMs
                    if (latencyMs != null) {
                        result.profileIds.forEach { profileId ->
                            successfulLatencies[profileId] = latencyMs
                        }
                    }
                    val completed = completedPings.addAndGet(result.profileIds.size)
                    if (publicationGate.shouldPublish(completed)) {
                        publishCheckProgress(
                            phase = ProxyCheckPhase.PING_ENDPOINTS,
                            phaseCompleted = completed,
                            phaseTotal = profileIds.size,
                            overallOffset = 0,
                            hostPingUpdates = if (latencyMs == null) {
                                emptyMap()
                            } else {
                                result.profileIds.associateWith { latencyMs }
                            },
                        )
                    }
                },
            ) { group ->
                val latencyMs = group.target?.takeIf { target ->
                    target.host.isNumericIpLiteral()
                }?.let { target ->
                    pingEndpoint(target.host, target.port)
                }
                EndpointPingResult(group.profileIds, latencyMs)
            }
        } finally {
            publishCheckProgress(
                phase = ProxyCheckPhase.PING_ENDPOINTS,
                phaseCompleted = completedPings.get(),
                phaseTotal = profileIds.size,
                overallOffset = 0,
                hostPingUpdates = successfulLatencies,
            )
        }
        val orderedSuccessfulLatencies = buildMap<String, Long> {
            profileIds.forEach { profileId ->
                successfulLatencies[profileId]?.let { latencyMs ->
                    put(profileId, latencyMs)
                }
            }
        }
        operation.update { state ->
            if (!state.isChecking || state.checkPhase != ProxyCheckPhase.PING_ENDPOINTS) {
                state
            } else {
                state.copy(
                    hostPingMs = state.hostPingMs + orderedSuccessfulLatencies,
                    checkCompleted = profileIds.size,
                    checkPhaseCompleted = profileIds.size,
                )
            }
        }
        return orderedSuccessfulLatencies
    }

    private suspend fun checkProfileTunnels(
        profileIds: List<String>,
        summariesById: Map<String, ProxyProfileSummary>,
        overallOffset: Int,
        endpointLatencies: Map<String, Long>?,
        deadlineNanos: Long,
    ): List<ProxyTunnelTestResult> {
        val completedTunnels = AtomicInteger(0)
        val publicationGate = CheckProgressPublicationGate(profileIds.size)
        val endpointUnavailableIds = if (endpointLatencies == null) {
            emptySet()
        } else {
            profileIds.filterTo(hashSetOf()) { profileId ->
                profileId !in endpointLatencies &&
                    summariesById[profileId]?.let { summary ->
                        summary.transport.tcpPingCanRejectTunnel() &&
                            summary.host.isNumericIpLiteral()
                    } == true
            }
        }
        val prioritizedProfileIds = prioritizeTunnelChecks(
            profileIds = profileIds,
            endpointLatencies = endpointLatencies,
            endpointUnavailableIds = endpointUnavailableIds,
        )
        fun publishCompleted(count: Int) {
            if (publicationGate.shouldPublish(count)) {
                publishCheckProgress(
                    phase = ProxyCheckPhase.TUNNELS,
                    phaseCompleted = count,
                    phaseTotal = profileIds.size,
                    overallOffset = overallOffset,
                )
            }
        }

        val immediateResults = ArrayList<ProxyTunnelTestResult>()
        val candidateIds = ArrayList<String>()
        prioritizedProfileIds.forEach { profileId ->
            val summary = summariesById[profileId]
            val result = when {
                summary?.transport == ProxyTransport.UNKNOWN ||
                    summary?.security == ProxySecurity.UNKNOWN -> ProxyTunnelTestResult(
                    profileId,
                    ProxyTestStatus.UNSUPPORTED,
                    message = "Unsupported transport configuration",
                    profileFingerprint = summary.fingerprint,
                )
                profileId in endpointUnavailableIds -> ProxyTunnelTestResult(
                    profileId,
                    ProxyTestStatus.UNAVAILABLE,
                    message = "Endpoint did not respond within ${HOST_PING_TIMEOUT_MS} ms",
                    profileFingerprint = summary?.fingerprint,
                )
                else -> null
            }
            if (result == null) candidateIds += profileId else immediateResults += result
        }

        if (immediateResults.isNotEmpty()) {
            withContext(NonCancellable) {
                proxyProfileRepository.saveTestResults(immediateResults)
            }
            publishCompleted(completedTunnels.addAndGet(immediateResults.size))
        }

        val profilesById = proxyProfileRepository.getByIds(candidateIds).associateBy { it.id }
        val missingResults = candidateIds.mapNotNull { profileId ->
            if (profileId in profilesById) {
                null
            } else {
                ProxyTunnelTestResult(
                    profileId,
                    ProxyTestStatus.NOT_TESTED,
                    message = "Profile disappeared before its tunnel check",
                )
            }
        }
        if (missingResults.isNotEmpty()) {
            // There is no row to update, and a same-ID row inserted concurrently is a new revision.
            immediateResults += missingResults
            publishCompleted(completedTunnels.addAndGet(missingResults.size))
        }
        val profilesToTest = candidateIds.mapNotNull(profilesById::get)
        if (profilesToTest.isEmpty()) return immediateResults

        return try {
            val batchResults = xrayCoreBridge.testBatch(
                profiles = profilesToTest,
                deadlineNanos = deadlineNanos,
                onResult = { publishCompleted(completedTunnels.incrementAndGet()) },
            ).map { result ->
                result.copy(
                    profileFingerprint = profilesById[result.profileId]?.fingerprint,
                )
            }
            // Keep previous per-profile statuses intact during the transient batch. One atomic
            // terminal transaction avoids persistent RUNNING rows after process death/cancellation.
            withContext(NonCancellable) { proxyProfileRepository.saveTestResults(batchResults) }
            immediateResults + batchResults
        } finally {
            publishCheckProgress(
                phase = ProxyCheckPhase.TUNNELS,
                phaseCompleted = completedTunnels.get(),
                phaseTotal = profileIds.size,
                overallOffset = overallOffset,
            )
        }
    }

    private fun publishCheckProgress(
        phase: ProxyCheckPhase,
        phaseCompleted: Int,
        phaseTotal: Int,
        overallOffset: Int,
        hostPingUpdates: Map<String, Long> = emptyMap(),
    ) {
        operation.update { state ->
            if (!state.isChecking || state.checkPhase != phase) {
                state
            } else {
                val boundedPhaseCompleted = phaseCompleted.coerceIn(0, phaseTotal)
                state.copy(
                    checkCompleted = maxOf(
                        state.checkCompleted,
                        overallOffset + boundedPhaseCompleted,
                    ).coerceAtMost(state.checkTotal),
                    checkPhaseCompleted = maxOf(
                        state.checkPhaseCompleted,
                        boundedPhaseCompleted,
                    ),
                    hostPingMs = state.hostPingMs + hostPingUpdates,
                )
            }
        }
    }

    private suspend fun pingEndpoint(host: String, port: Int): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val startedAt = System.nanoTime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), HOST_PING_TIMEOUT_MS)
            }
            ((System.nanoTime() - startedAt) / NANOS_IN_MILLIS).coerceAtLeast(1L)
        }.getOrNull()
    }
}

private data class EndpointPingTarget(
    val host: String,
    val port: Int,
)

private data class EndpointPingGroup(
    val target: EndpointPingTarget?,
    val profileIds: List<String>,
)

private data class EndpointPingResult(
    val profileIds: List<String>,
    val latencyMs: Long?,
)

private fun groupEndpointPings(
    profileIds: List<String>,
    summariesById: Map<String, ProxyProfileSummary>,
): List<EndpointPingGroup> {
    val profileIdsByTarget = linkedMapOf<EndpointPingTarget?, MutableList<String>>()
    profileIds.forEach { profileId ->
        val target = summariesById[profileId]?.let { summary ->
            EndpointPingTarget(summary.host.trim().lowercase(Locale.ROOT), summary.port)
        }
        profileIdsByTarget.getOrPut(target, ::mutableListOf) += profileId
    }
    return profileIdsByTarget.map { (target, groupedProfileIds) ->
        EndpointPingGroup(target, groupedProfileIds)
    }
}

/** Runs a continuous bounded worker pool and preserves input order in the returned list. */
internal suspend fun <T, R> mapConcurrentOrdered(
    values: List<T>,
    maxConcurrency: Int,
    onResult: suspend (index: Int, result: R) -> Unit = { _, _ -> },
    transform: suspend (T) -> R,
): List<R> {
    require(maxConcurrency > 0) { "Concurrency must be positive" }
    if (values.isEmpty()) return emptyList()

    val nextIndex = AtomicInteger(0)
    val results = AtomicReferenceArray<ConcurrentMapResult<R>?>(values.size)
    coroutineScope {
        List(minOf(maxConcurrency, values.size)) {
            launch {
                while (true) {
                    val index = nextIndex.getAndIncrement()
                    if (index >= values.size) break
                    val result = transform(values[index])
                    results.set(index, ConcurrentMapResult(result))
                    onResult(index, result)
                }
            }
        }.joinAll()
    }
    return List(values.size) { index ->
        checkNotNull(results.get(index)) { "Missing concurrent result at index $index" }.value
    }
}

private data class ConcurrentMapResult<R>(val value: R)

internal fun prioritizeTunnelChecks(
    profileIds: List<String>,
    endpointLatencies: Map<String, Long>?,
    endpointUnavailableIds: Set<String>,
): List<String> {
    if (endpointLatencies == null) return profileIds
    return profileIds.sortedWith(
        compareBy<String> { profileId -> profileId !in endpointUnavailableIds }
            .thenBy { profileId -> endpointLatencies[profileId] ?: Long.MAX_VALUE },
    )
}

internal fun ProxyTransport.tcpPingCanRejectTunnel(): Boolean {
    return this != ProxyTransport.MKCP && this != ProxyTransport.HYSTERIA
}

internal fun String.isNumericIpLiteral(): Boolean {
    val candidate = trim().removePrefix("[").removeSuffix("]").substringBefore('%')
    if (candidate.contains(':')) {
        return candidate.isNotEmpty() && candidate.all { character ->
            character == ':' || character == '.' || character.isDigit() ||
                character in 'a'..'f' || character in 'A'..'F'
        }
    }
    val octets = candidate.split('.')
    return octets.size == 4 && octets.all { octet ->
        val value = octet.toIntOrNull()
        octet.isNotEmpty() && octet.length <= 3 && value != null && value in 0..255
    }
}

internal class CheckProgressPublicationGate(
    private val total: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val stride = checkProgressPublishStride(total)
    private var lastPublishedCompleted = 0
    private var lastPublishedAtNanos = nanoTime()

    @Synchronized
    fun shouldPublish(completed: Int): Boolean {
        if (completed <= lastPublishedCompleted || completed <= 0 || total <= 0) return false
        val now = nanoTime()
        val countDue = completed - lastPublishedCompleted >= stride
        val timeDue = now - lastPublishedAtNanos >= PROGRESS_MAX_SILENCE_NANOS
        if (completed < total && !countDue && !timeDue) return false
        lastPublishedCompleted = completed.coerceAtMost(total)
        lastPublishedAtNanos = now
        return true
    }
}

internal fun checkProgressPublishStride(total: Int): Int {
    if (total <= 0) return 1
    return (total / MAX_PROGRESS_PUBLICATIONS_PER_PHASE +
        if (total % MAX_PROGRESS_PUBLICATIONS_PER_PHASE == 0) 0 else 1)
        .coerceAtLeast(1)
}

private data class OperationState(
    val isSyncing: Boolean = false,
    val isRemovingUnavailable: Boolean = false,
    val isChecking: Boolean = false,
    val checkCompleted: Int = 0,
    val checkTotal: Int = 0,
    val checkPhase: ProxyCheckPhase? = null,
    val checkPhaseCompleted: Int = 0,
    val checkPhaseTotal: Int = 0,
    val hostPingMs: Map<String, Long> = emptyMap(),
    val message: String? = null,
)

private data class DialogState(
    val editor: ProxyEditorState? = null,
    val showBulkImport: Boolean = false,
    val showRemoveUnavailableConfirmation: Boolean = false,
)

private data class ProfileListState(
    val profiles: List<ProxyProfileSummary>,
    val allProfileIds: Set<String>,
    val query: String,
    val pinnedOnly: Boolean,
    val protocolFilter: ProxyProtocol?,
    val selectedIds: Set<String>,
    val unavailableUnpinnedCount: Int,
)

private data class AuxiliaryState(
    val operation: OperationState,
    val dialogs: DialogState,
    val vpnState: VpnConnectionState,
    val showNoSelectedAppsDialog: Boolean,
    val xrayCoreAvailable: Boolean,
)

internal fun removedUnavailableMessage(removed: Int): String {
    return when (removed) {
        0 -> "No unavailable tunnels to remove"
        1 -> "Removed 1 unavailable tunnel"
        else -> "Removed $removed unavailable tunnels"
    }
}

private fun ProxyProfileSummary.matchesNormalized(query: String): Boolean =
    name.contains(query, ignoreCase = true) ||
        host.contains(query, ignoreCase = true) ||
        protocol.name.contains(query, ignoreCase = true) ||
        transport.name.contains(query, ignoreCase = true) ||
        security.name.contains(query, ignoreCase = true)

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

private fun VpnConnectionState.ownsXrayRuntime(): Boolean {
    return activeTransport == VpnTransportType.XRAY
}

private data class SplitTunnelSettings(
    val vpnMode: VpnMode,
    val selectedAppPackages: Set<String>,
)

private const val SETTINGS_CHANGE_DEBOUNCE_MS = 250L
private const val PROFILE_SEARCH_DEBOUNCE_MS = 200L
private const val HOST_PING_TIMEOUT_MS = 1_500
private const val HOST_PING_CONCURRENCY = 12
// A live 10 Hz-ish counter is visually continuous while avoiding hundreds of Compose updates.
private const val MAX_PROGRESS_PUBLICATIONS_PER_PHASE = 100
private const val PROGRESS_MAX_SILENCE_NANOS = 100_000_000L
private const val NANOS_IN_MILLIS = 1_000_000L
private const val TRANSPORT_SWITCH_TIMEOUT_MS = 2_000L
