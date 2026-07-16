package com.stansful.sshvpnclient.ui.smartconnect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.data.local.SmartConnectStateStore
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.AppUpdateCheckResult
import com.stansful.sshvpnclient.domain.model.AppUpdateDownloadState
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.SmartConnectPhase
import com.stansful.sshvpnclient.domain.model.SmartConnectState
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.model.XrayCoreAsset
import com.stansful.sshvpnclient.domain.model.XrayCoreRelease
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.AppUpdateDownloader
import com.stansful.sshvpnclient.domain.repository.AppUpdateRepository
import com.stansful.sshvpnclient.domain.repository.SmartProxyProfileRepository
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.domain.repository.XrayCoreUpdateRepository
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectSmartVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.DisconnectVpnUseCase
import com.stansful.sshvpnclient.ui.common.AppUpdateUiState
import com.stansful.sshvpnclient.xray.XrayCoreBridge
import com.stansful.sshvpnclient.xray.XrayCoreInstallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SmartXrayCoreUpdateUiState(
    val runtimeAbi: String = "",
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadingAbi: String? = null,
    val release: XrayCoreRelease? = null,
    val statusMessage: String? = null,
) {
    val compatibleAsset: XrayCoreAsset?
        get() = release?.assets?.firstOrNull { asset -> asset.abi == runtimeAbi }
}

data class SmartConnectUiState(
    val rankedProfiles: List<ProxyProfileSummary> = emptyList(),
    val selectedProfile: ProxyProfileSummary? = null,
    val workflow: SmartConnectState = SmartConnectState(),
    val appSettings: AppSettings = AppSettings(),
    val vpnState: VpnConnectionState = VpnConnectionState(),
    val xrayCoreAvailable: Boolean = false,
    val xrayCoreUpdate: SmartXrayCoreUpdateUiState = SmartXrayCoreUpdateUiState(),
    val updateState: AppUpdateUiState = AppUpdateUiState(),
    val isStartPending: Boolean = false,
    val showNoSelectedAppsDialog: Boolean = false,
    val actionMessage: String? = null,
) {
    val ownsVpnSession: Boolean
        get() = vpnState.sessionOwner == VpnSessionOwner.SMART_CONNECT &&
            vpnState.status in ACTIVE_VPN_STATUSES

    val isActive: Boolean
        get() = workflow.desiredActive || ownsVpnSession || isStartPending

    val canStart: Boolean
        get() = !isActive &&
            xrayCoreAvailable &&
            !xrayCoreUpdate.isDownloading &&
            workflow.phase != SmartConnectPhase.STOPPING

    val xrayRuntimeInUse: Boolean
        get() = isActive || vpnState.activeTransport == VpnTransportType.XRAY

    val checkingProgress: Float?
        get() = if (workflow.phase == SmartConnectPhase.CHECKING && workflow.checkTotal > 0) {
            workflow.checkCompleted.toFloat()
                .div(workflow.checkTotal.toFloat())
                .coerceIn(0f, 1f)
        } else {
            null
        }

    val visibleMessage: String?
        get() = actionMessage
            ?: workflow.message
            ?: vpnState.errorMessage.takeIf {
                workflow.phase == SmartConnectPhase.ERROR || vpnState.sessionOwner == VpnSessionOwner.SMART_CONNECT
            }

    private companion object {
        val ACTIVE_VPN_STATUSES = setOf(
            VpnConnectionStatus.CONNECTING,
            VpnConnectionStatus.CONNECTED,
            VpnConnectionStatus.RECONNECTING,
            VpnConnectionStatus.DISCONNECTING,
        )
    }
}

class SmartConnectViewModel(
    private val proxyProfileRepository: SmartProxyProfileRepository,
    private val smartConnectStateStore: SmartConnectStateStore,
    private val appSettingsRepository: AppSettingsRepository,
    private val vpnConnectionRepository: VpnConnectionRepository,
    private val connectSmartVpnUseCase: ConnectSmartVpnUseCase,
    private val disconnectVpnUseCase: DisconnectVpnUseCase,
    private val xrayCoreBridge: XrayCoreBridge,
    private val xrayCoreUpdateRepository: XrayCoreUpdateRepository,
    private val appUpdateRepository: AppUpdateRepository,
    private val appUpdateDownloader: AppUpdateDownloader,
) : ViewModel() {
    private val showNoSelectedAppsDialog = MutableStateFlow(false)
    private val actionMessage = MutableStateFlow<String?>(null)
    private val xrayCoreAvailable = MutableStateFlow(false)
    private val xrayCoreUpdate = MutableStateFlow(
        SmartXrayCoreUpdateUiState(runtimeAbi = xrayCoreUpdateRepository.runtimeAbi),
    )
    private val appUpdateState = MutableStateFlow(AppUpdateUiState())
    private val isStartPending = MutableStateFlow(false)
    private var startJob: Job? = null
    private var xrayCoreDownloadJob: Job? = null
    private var updateCheckJob: Job? = null

    private val rankedProfiles = proxyProfileRepository.observeSummaries()
        .map(::rankAvailableSmartProfiles)
        .flowOn(Dispatchers.Default)

    private val contentState = combine(
        rankedProfiles,
        smartConnectStateStore.state,
        appSettingsRepository.settings,
        vpnConnectionRepository.state,
        xrayCoreAvailable,
    ) { profiles, workflow, settings, vpnState, coreAvailable ->
        val selectedProfile = profiles.firstOrNull { profile ->
            profile.id == workflow.activeProfileId
        } ?: profiles.firstOrNull(ProxyProfileSummary::isSelected)
            ?: profiles.firstOrNull()

        SmartConnectUiState(
            rankedProfiles = profiles,
            selectedProfile = selectedProfile,
            workflow = workflow,
            appSettings = settings,
            vpnState = vpnState,
            xrayCoreAvailable = coreAvailable,
        )
    }

    private val updateStates = combine(xrayCoreUpdate, appUpdateState, ::SmartUpdateStates)

    val uiState = combine(
        contentState,
        showNoSelectedAppsDialog,
        actionMessage,
        isStartPending,
        updateStates,
    ) { content, showNoSelectedApps, message, startPending, updates ->
        content.copy(
            showNoSelectedAppsDialog = showNoSelectedApps,
            actionMessage = message,
            isStartPending = startPending,
            xrayCoreUpdate = updates.xrayCore,
            updateState = updates.app,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SmartConnectUiState(),
    )

    init {
        refreshXrayCoreAvailability()
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

    /** Validates local UI preconditions before Android's VPN permission dialog is opened. */
    fun prepareStart(): Boolean {
        val state = uiState.value
        if (state.isActive || isStartPending.value || startJob?.isActive == true) return false
        if (state.appSettings.requiresSelectedAppsButHasNone()) {
            showNoSelectedAppsDialog.value = true
            return false
        }
        if (!state.xrayCoreAvailable) {
            actionMessage.value = "Xray runtime core is not installed"
            refreshXrayCoreAvailability()
            return false
        }
        if (xrayCoreDownloadJob != null || xrayCoreUpdate.value.isDownloading) {
            actionMessage.value = "Wait until the Xray core installation finishes"
            return false
        }
        actionMessage.value = null
        return true
    }

    fun start() {
        if (!prepareStart()) return
        launchSmartStart()
    }

    /**
     * Reconciles the persisted desired flag after process death/reboot. Android cannot reliably
     * redeliver a VPN foreground service across every OEM/force-stop path, so opening the Smart
     * tab restores it idempotently when permission still exists, or clears the stale flag and lets
     * the next explicit Start request show the permission dialog.
     */
    fun reconcilePersistedSession(vpnPermissionGranted: Boolean) {
        if (!smartConnectStateStore.desiredActive || isStartPending.value || startJob?.isActive == true) {
            return
        }
        val vpnState = vpnConnectionRepository.currentState
        if (vpnState.sessionOwner == VpnSessionOwner.SMART_CONNECT &&
            vpnState.status in RESTORABLE_ACTIVE_STATUSES
        ) {
            return
        }
        if (vpnState.sessionOwner != null) {
            smartConnectStateStore.stop("Smart Connect restore cancelled because another VPN is active")
            return
        }
        if (!vpnPermissionGranted) {
            smartConnectStateStore.stop("Tap Start to restore Smart Connect")
            actionMessage.value = "VPN permission is required to restore Smart Connect"
            return
        }
        if (xrayCoreDownloadJob != null || xrayCoreUpdate.value.isDownloading) return
        launchSmartStart()
    }

    private fun launchSmartStart() {
        isStartPending.value = true
        lateinit var launchedJob: Job
        launchedJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                connectSmartVpnUseCase()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                smartConnectStateStore.fail(
                    message = error.message ?: "Unable to start Smart Connect",
                    keepDesiredActive = false,
                )
            } finally {
                if (startJob === launchedJob) {
                    startJob = null
                    isStartPending.value = false
                }
            }
        }
        startJob = launchedJob
        launchedJob.start()
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        isStartPending.value = false
        smartConnectStateStore.stop("Smart Connect stopped")
        if (vpnConnectionRepository.currentState.sessionOwner == VpnSessionOwner.SMART_CONNECT) {
            disconnectVpnUseCase()
        }
    }

    fun onVpnPermissionDenied() {
        actionMessage.value = "VPN permission is required for Smart Connect"
    }

    fun dismissNoSelectedAppsDialog() {
        showNoSelectedAppsDialog.value = false
    }

    fun clearActionMessage() {
        actionMessage.value = null
    }

    fun refreshXrayCoreAvailability() {
        viewModelScope.launch(Dispatchers.IO) {
            xrayCoreAvailable.value = xrayCoreBridge.isAvailable
        }
    }

    fun checkXrayCoreUpdates() {
        val state = xrayCoreUpdate.value
        if (state.isChecking || state.isDownloading) return
        xrayCoreUpdate.update { it.copy(isChecking = true, statusMessage = null) }
        viewModelScope.launch {
            try {
                val release = xrayCoreUpdateRepository.loadLatestRelease()
                val runtimeAsset = release.assets.firstOrNull { asset ->
                    asset.abi == xrayCoreUpdateRepository.runtimeAbi
                }
                xrayCoreUpdate.update {
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
            } catch (error: CancellationException) {
                xrayCoreUpdate.update { it.copy(isChecking = false) }
                throw error
            } catch (error: Exception) {
                xrayCoreUpdate.update {
                    it.copy(
                        isChecking = false,
                        statusMessage = error.message ?: "Unable to check Xray core updates",
                    )
                }
            }
        }
    }

    fun downloadXrayCore(asset: XrayCoreAsset) {
        val state = xrayCoreUpdate.value
        if (xrayCoreDownloadJob != null || state.isDownloading || state.isChecking) return
        if (isXrayRuntimeInUse()) {
            xrayCoreUpdate.update {
                it.copy(statusMessage = "Disconnect the active Xray VPN before updating the core")
            }
            return
        }
        if (asset.abi != xrayCoreUpdateRepository.runtimeAbi) {
            xrayCoreUpdate.update {
                it.copy(
                    statusMessage = "Xray core ${asset.abi} is not compatible with runtime ABI " +
                        xrayCoreUpdateRepository.runtimeAbi,
                )
            }
            return
        }

        xrayCoreUpdate.update {
            it.copy(
                isDownloading = true,
                downloadingAbi = asset.abi,
                statusMessage = "Downloading Xray core for ${asset.abi}",
            )
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var installResult: XrayCoreInstallResult? = null
            try {
                val file = xrayCoreUpdateRepository.download(asset)
                check(!isXrayRuntimeInUse()) {
                    "An Xray VPN started during the download. Disconnect it and try installing again."
                }
                file.inputStream().use { input ->
                    installResult = xrayCoreBridge.installCore(input)
                }
                xrayCoreAvailable.value = xrayCoreBridge.isAvailable
                xrayCoreUpdate.update {
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
            } catch (error: CancellationException) {
                xrayCoreUpdate.update {
                    it.copy(
                        isDownloading = false,
                        downloadingAbi = null,
                        statusMessage = "Xray core download cancelled",
                    )
                }
                throw error
            } catch (error: Exception) {
                xrayCoreUpdate.update {
                    it.copy(
                        isDownloading = false,
                        downloadingAbi = null,
                        statusMessage = error.message ?: "Unable to install Xray core",
                    )
                }
            } finally {
                xrayCoreDownloadJob = null
            }
        }
        xrayCoreDownloadJob = job
        job.start()
    }

    fun cancelXrayCoreDownload() {
        val job = xrayCoreDownloadJob ?: return
        job.cancel()
        xrayCoreUpdate.update {
            it.copy(
                isDownloading = false,
                downloadingAbi = null,
                statusMessage = "Xray core download cancelled",
            )
        }
    }

    fun checkForUpdates() {
        if (updateCheckJob?.isActive == true) return
        updateCheckJob = viewModelScope.launch {
            appUpdateState.update { it.copy(isChecking = true, statusMessage = null) }
            try {
                when (val result = appUpdateRepository.checkForUpdate(force = true)) {
                    is AppUpdateCheckResult.Available -> appUpdateState.update {
                        it.copy(
                            isChecking = false,
                            availableUpdate = result.update,
                            statusMessage = null,
                        )
                    }
                    AppUpdateCheckResult.UpToDate -> appUpdateState.update {
                        it.copy(
                            isChecking = false,
                            availableUpdate = null,
                            statusMessage = "shadow-ssh is up to date",
                        )
                    }
                    AppUpdateCheckResult.NotDue -> appUpdateState.update {
                        it.copy(isChecking = false)
                    }
                }
            } catch (error: CancellationException) {
                appUpdateState.update { it.copy(isChecking = false) }
                throw error
            } catch (error: Exception) {
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
        val update = appUpdateState.value.availableUpdate
        if (update != null) {
            appUpdateDownloader.download(update)
            appUpdateState.update { it.copy(availableUpdate = null, statusMessage = null) }
        } else {
            appUpdateDownloader.resume()
            appUpdateState.update { it.copy(statusMessage = null) }
        }
    }

    fun onUpdateActionFailed(message: String) {
        appUpdateState.update { it.copy(statusMessage = message) }
    }

    fun setShowLogsOnSmartConnect(show: Boolean) {
        appSettingsRepository.setShowLogsOnSmartConnect(show)
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

    private fun isXrayRuntimeInUse(): Boolean {
        return smartConnectStateStore.state.value.desiredActive ||
            isStartPending.value ||
            vpnConnectionRepository.currentState.activeTransport == VpnTransportType.XRAY
    }

    private companion object {
        val RESTORABLE_ACTIVE_STATUSES = setOf(
            VpnConnectionStatus.CONNECTING,
            VpnConnectionStatus.CONNECTED,
            VpnConnectionStatus.RECONNECTING,
            VpnConnectionStatus.DISCONNECTING,
        )
    }
}

private data class SmartUpdateStates(
    val xrayCore: SmartXrayCoreUpdateUiState,
    val app: AppUpdateUiState,
)

internal fun rankAvailableSmartProfiles(
    profiles: List<ProxyProfileSummary>,
): List<ProxyProfileSummary> {
    return profiles.asSequence()
        .filter { profile ->
            profile.lastTestStatus == ProxyTestStatus.AVAILABLE &&
                !profile.isStale &&
                !profile.name.contains(SMART_CONNECT_EXCLUDED_NAME_MARKER)
        }
        .sortedWith(
            compareBy<ProxyProfileSummary> { profile -> profile.lastLatencyMs ?: Long.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { profile -> profile.name },
        )
        .toList()
}

private fun AppSettings.requiresSelectedAppsButHasNone(): Boolean {
    return vpnMode == VpnMode.SELECTED_APPS && selectedAppPackages.isEmpty()
}

private const val SMART_CONNECT_EXCLUDED_NAME_MARKER = "🇷🇺"
