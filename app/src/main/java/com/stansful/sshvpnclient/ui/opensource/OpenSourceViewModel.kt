package com.stansful.sshvpnclient.ui.opensource

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppUpdateState
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.ProxyImportResult
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.model.XrayCoreAsset
import com.stansful.sshvpnclient.domain.model.XrayCoreRelease
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.AppUpdateCoordinator
import com.stansful.sshvpnclient.domain.repository.ProxyProfileRepository
import com.stansful.sshvpnclient.domain.repository.ProxySourceSynchronizer
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.domain.repository.XrayCoreUpdateRepository
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyParseResult
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
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
import kotlinx.coroutines.cancelAndJoin
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

/**
 * The route editor (Add routes / Edit route sheet). [loading] while the stored link of [profileId] is
 * being decrypted, [saving] while a save runs; [error] is shown inside the sheet (the sheet stays open).
 */
data class ProxyEditorState(
    val profileId: String? = null,
    val rawUri: String = "",
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

enum class ProxyCheckPhase(val displayName: String) {
    PING_ENDPOINTS("Pinging endpoints"),
    TUNNELS("Checking tunnels"),
}

/** Library filter chips. [PINNED] is the old "Pinned only" filter. */
enum class RouteStatusFilter {
    ALL,
    AVAILABLE,
    PINNED,
    NOT_CHECKED,
}

/** Counts over the whole library (independent of search and filters). */
data class RouteCounts(
    val total: Int = 0,
    val available: Int = 0,
    val unavailable: Int = 0,
    val unsupported: Int = 0,
    val notChecked: Int = 0,
    val pinned: Int = 0,
)

enum class RoutesNoticeTone {
    Success,
    Info,
    Error,
    Locked,
}

/**
 * How the last list refresh started in this process ended; [id] grows with every refresh, so a screen
 * can tell the refresh it started from older ones. [failureMessage] is null when it worked.
 */
data class LibrarySyncOutcome(
    val id: Long,
    val failureMessage: String? = null,
)

/** One operation result for a toast; [id] makes repeated identical results distinct. */
data class RoutesNotice(
    val id: Long,
    val text: String,
    val detail: String? = null,
    val tone: RoutesNoticeTone = RoutesNoticeTone.Success,
)

data class OpenSourceUiState(
    val profiles: List<ProxyProfileSummary> = emptyList(),
    val allProfileIds: Set<String> = emptySet(),
    val query: String = "",
    val pinnedOnly: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val unavailableUnpinnedCount: Int = 0,
    val isSyncing: Boolean = false,
    /** The last finished refresh of the public list (null until one finishes in this process). */
    val lastSync: LibrarySyncOutcome? = null,
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
    val updateState: AppUpdateState = AppUpdateState(),
    val xrayCoreUpdateState: XrayCoreUpdateUiState = XrayCoreUpdateUiState(),
    val statusFilter: RouteStatusFilter = RouteStatusFilter.ALL,
    val counts: RouteCounts = RouteCounts(),
    val activeProfile: ProxyProfileSummary? = null,
    val notice: RoutesNotice? = null,
    val checkingRouteId: String? = null,
    val checkAvailableSoFar: Int = 0,
    val selectionActive: Boolean = false,
    /** False until the library has been read once (so an empty library doesn't flash on entry). */
    val libraryLoaded: Boolean = false,
    /** The whole library in display order ([profiles] is the searched/filtered part of it). */
    val library: List<ProxyProfileSummary> = emptyList(),
) {
    val checkProgressText: String?
        get() {
            val phase = checkPhase ?: return null
            return "${phase.displayName} $checkPhaseCompleted/$checkPhaseTotal · " +
                "overall $checkCompleted/$checkTotal"
        }

    /** Multi-select is on: entered explicitly ([selectionActive]) or by long-pressing a route. */
    val selectionMode: Boolean get() = selectionActive || selectedIds.isNotEmpty()

    /** A check of every route is running (not a single-route check). */
    val isCheckingAll: Boolean get() = isChecking && checkingRouteId == null

    /** A start failure of the active route, whether or not search or a filter hides it. */
    val activeRouteErrorMessage: String?
        get() {
            val profileId = activeProfile?.id ?: return null
            return vpnState.errorMessage.takeIf {
                vpnState.status == VpnConnectionStatus.ERROR &&
                    vpnState.activeTransport == null &&
                    vpnState.activeConfigId == profileId
            }
        }
    val canRemoveUnavailable: Boolean
        get() = unavailableUnpinnedCount > 0 &&
            !isSyncing &&
            !isChecking &&
            !isRemovingUnavailable &&
            !anyXrayRuntimeActive
    /**
     * The active route. The route library's search and filters only narrow the list: Home, the
     * connection and its errors use the active route whether or not a filter hides it.
     */
    val selectedProfile: ProxyProfileSummary?
        get() = activeProfile ?: profiles.firstOrNull(ProxyProfileSummary::isSelected)
    val xrayConnected: Boolean
        get() = vpnState.activeTransport == VpnTransportType.XRAY &&
            vpnState.sessionOwner == VpnSessionOwner.OPEN_SOURCE &&
            vpnState.status in setOf(
                VpnConnectionStatus.CONNECTING,
                VpnConnectionStatus.CONNECTED,
                VpnConnectionStatus.RECONNECTING,
            )
    val anyXrayRuntimeActive: Boolean
        get() = vpnState.activeTransport == VpnTransportType.XRAY &&
            vpnState.status in setOf(
                VpnConnectionStatus.CONNECTING,
                VpnConnectionStatus.CONNECTED,
                VpnConnectionStatus.RECONNECTING,
                VpnConnectionStatus.DISCONNECTING,
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

    /**
     * Errors are published without an owner, so only the route id tells this tab's start and
     * runtime failures (the quick settings tile's included) apart from another tab's.
     */
    val openSourceErrorMessage: String?
        get() {
            val profileId = selectedProfile?.id ?: return null
            return vpnState.errorMessage.takeIf {
                vpnState.status == VpnConnectionStatus.ERROR &&
                    vpnState.activeTransport == null &&
                    vpnState.activeConfigId == profileId
            }
        }
}

data class XrayCoreUpdateUiState(
    val runtimeAbi: String = "",
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadingAbi: String? = null,
    val release: XrayCoreRelease? = null,
    /** Text for the engine's status line; [statusKind] says what it reports (null together with it). */
    val statusMessage: String? = null,
    val statusKind: XrayCoreStatusKind? = null,
)

/** What [XrayCoreUpdateUiState.statusMessage] reports, so screens can branch without parsing the text. */
enum class XrayCoreStatusKind {
    /** The latest release has an engine for this phone. */
    RELEASE_FOUND,

    /** The latest release has no engine for this phone (or none at all). */
    NO_ASSET,

    /** Looking up the latest release failed; the message is the error. */
    CHECK_FAILED,

    /** The download was refused (a VPN uses the engine, or the ABI does not fit); the message says why. */
    DOWNLOAD_BLOCKED,

    DOWNLOADING,

    /** Installed and usable right away. */
    INSTALLED,

    /** The same engine was already installed. */
    ALREADY_INSTALLED,

    /** Installed over a loaded engine: it is used after the app restarts. */
    INSTALLED_AFTER_RESTART,

    CANCELLED,

    /** Downloading or installing failed; the message is the error. */
    INSTALL_FAILED,
}

@OptIn(FlowPreview::class)
class OpenSourceViewModel(
    private val proxyProfileRepository: ProxyProfileRepository,
    private val proxySourceSynchronizer: ProxySourceSynchronizer,
    private val xrayCoreBridge: XrayCoreBridge,
    private val appSettingsRepository: AppSettingsRepository,
    private val connectProxyVpnUseCase: ConnectProxyVpnUseCase,
    private val disconnectVpnUseCase: DisconnectVpnUseCase,
    private val vpnConnectionRepository: VpnConnectionRepository,
    private val appUpdateCoordinator: AppUpdateCoordinator,
    private val xrayCoreUpdateRepository: XrayCoreUpdateRepository,
    /** Shared with Auto: raised while [downloadXrayCore] runs, so Auto waits for the new engine. */
    private val xrayCoreInstallInProgress: MutableStateFlow<Boolean> = MutableStateFlow(false),
    /** Auto wants its session (it may still be checking routes, before its VPN is up). */
    private val isAutoActive: () -> Boolean = { false },
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
    private val statusFilter = MutableStateFlow(RouteStatusFilter.ALL)
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val selectionActive = MutableStateFlow(false)
    private val linkParser = ProxyShareLinkParser()
    private var noticeSequence = 0L
    private var syncSequence = 0L
    private val operation = MutableStateFlow(OperationState())
    private val dialogState = MutableStateFlow(DialogState())
    private val showNoSelectedAppsDialog = MutableStateFlow(false)
    private val xrayCoreAvailable = MutableStateFlow(false)
    private val xrayCoreUpdateState = MutableStateFlow(
        XrayCoreUpdateUiState(runtimeAbi = xrayCoreUpdateRepository.runtimeAbi),
    )
    private var checkJob: Job? = null
    private var xrayCoreDownloadJob: Job? = null
    private var settingsReconnectJob: Job? = null
    private var settingsReconnectStarted = false

    init {
        // Loading an installed runtime may touch disk and construct a DexClassLoader.
        refreshXrayCoreAvailability()
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
    }

    fun refreshXrayCoreAvailability() {
        viewModelScope.launch(Dispatchers.IO) {
            xrayCoreAvailable.value = xrayCoreBridge.isAvailable
        }
    }

    private val selection = combine(selectedIds, selectionActive) { ids, active -> SelectionState(ids, active) }

    private val filteredProfileListState = combine(
        proxyProfileRepository.observeSummaries(),
        normalizedSearchQuery,
        statusFilter,
        selection,
    ) { profiles, normalizedQuery, status, selectionState ->
        val filteredProfiles = if (status == RouteStatusFilter.ALL && normalizedQuery.isBlank()) {
            profiles
        } else {
            profiles.filter { profile ->
                profile.matchesStatus(status) &&
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
            statusFilter = status,
            selectedIds = selectionState.ids.filterTo(linkedSetOf()) { id -> id in allProfileIds },
            selectionActive = selectionState.active,
            unavailableUnpinnedCount = unavailableUnpinnedCount,
            counts = routeCounts(profiles),
            activeProfile = profiles.firstOrNull(ProxyProfileSummary::isSelected),
            library = profiles,
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
        appUpdateCoordinator.state,
        xrayCoreUpdateState,
    ) { profileState, auxiliary, appSettings, updateState, coreUpdateState ->
        val operation = auxiliary.operation
        val dialogs = auxiliary.dialogs
        OpenSourceUiState(
            profiles = profileState.profiles,
            allProfileIds = profileState.allProfileIds,
            query = profileState.query,
            pinnedOnly = profileState.statusFilter == RouteStatusFilter.PINNED,
            selectedIds = profileState.selectedIds,
            unavailableUnpinnedCount = profileState.unavailableUnpinnedCount,
            statusFilter = profileState.statusFilter,
            counts = profileState.counts,
            activeProfile = profileState.activeProfile,
            selectionActive = profileState.selectionActive,
            libraryLoaded = true,
            library = profileState.library,
            notice = operation.notice,
            checkingRouteId = operation.checkingRouteId,
            checkAvailableSoFar = operation.checkAvailableSoFar,
            isSyncing = operation.isSyncing,
            lastSync = operation.lastSync,
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

    fun setStatusFilter(value: RouteStatusFilter) {
        statusFilter.value = value
    }

    fun synchronize(force: Boolean = true) {
        if (operation.value.isSyncing || operation.value.isRemovingUnavailable) return
        viewModelScope.launch {
            val wasEmpty = uiState.value.allProfileIds.isEmpty()
            val activeBefore = uiState.value.activeProfile?.id
            operation.update { it.copy(isSyncing = true, message = null, notice = null) }
            try {
                val result = proxySourceSynchronizer.synchronize(force = force)
                operation.update { it.copy(isSyncing = false, lastSync = nextSyncOutcome(failureMessage = null)) }
                if (result.notModified) {
                    notify("List is already up to date")
                } else {
                    val active = proxyProfileRepository.getSelected()
                    val newlyActive = active?.takeIf { it.id != activeBefore }
                    val imported = result.importResult
                    if (wasEmpty && imported.added > 0) {
                        val activeLine = if (active != null) "${active.name} is now active. " else ""
                        notify(
                            text = "${imported.added} ${plural(imported.added, "route", "routes")} added",
                            detail = activeLine + "Run a check to see which ones work.",
                        )
                    } else {
                        notify(
                            text = "List updated",
                            detail = importSummary(imported) +
                                (newlyActive?.let { ". ${it.name} is now active." } ?: ""),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val detail = error.message ?: "Check your connection and try again."
                operation.update {
                    it.copy(isSyncing = false, lastSync = nextSyncOutcome("Couldn’t refresh the list. $detail"))
                }
                notify(
                    text = "Couldn’t refresh the list",
                    detail = detail,
                    tone = RoutesNoticeTone.Error,
                )
            }
        }
    }

    fun showBulkImport() {
        dialogState.update { it.copy(showBulkImport = true) }
    }

    fun importClipboard(text: String) {
        importText(text, ProxyProfileSource.CLIPBOARD)
        dismissBulkImport()
    }

    private fun dismissBulkImport() {
        dialogState.update { it.copy(showBulkImport = false) }
    }

    fun openEditor(profileId: String? = null) {
        if (profileId == null) {
            dialogState.update { it.copy(editor = ProxyEditorState()) }
            return
        }
        dialogState.update { it.copy(editor = ProxyEditorState(profileId, loading = true)) }
        viewModelScope.launch {
            val raw = runCatching { proxyProfileRepository.getById(profileId)?.rawUri }.getOrNull().orEmpty()
            dialogState.update { state ->
                if (state.editor?.profileId != profileId) {
                    state
                } else {
                    state.copy(editor = ProxyEditorState(profileId, raw))
                }
            }
        }
    }

    fun updateEditor(value: String) {
        dialogState.update { state -> state.copy(editor = state.editor?.copy(rawUri = value, error = null)) }
    }

    /** Closes the Add routes sheet: the editor and the clipboard import at once. */
    fun dismissAddRoutes() {
        dialogState.update { it.copy(editor = null, showBulkImport = false) }
    }

    /**
     * Saves the editor: Add imports its text as manual routes, Edit replaces the link of its route.
     * Success closes the editor and posts a notice; a failure stays in the open editor as its error.
     */
    fun saveEditor() {
        val editor = dialogState.value.editor ?: return
        if (editor.saving || editor.loading) return
        updateEditorState { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val hadActiveRoute = proxyProfileRepository.getSelected()?.isStale == false
            val existing = editor.profileId?.let { id ->
                runCatching { proxyProfileRepository.getById(id) }.getOrNull()
            }
            val result = try {
                if (editor.profileId == null) {
                    proxyProfileRepository.import(editor.rawUri, ProxyProfileSource.MANUAL)
                } else {
                    proxyProfileRepository.update(editor.profileId, editor.rawUri)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                updateEditorState { it.copy(saving = false, error = EDITOR_STORAGE_ERROR) }
                return@launch
            }
            val error = editorSaveError(result, editing = editor.profileId != null, routeExists = existing != null)
            if (error != null) {
                updateEditorState { it.copy(saving = false, error = error) }
                return@launch
            }
            dismissAddRoutes()
            val name = (linkParser.parse(editor.rawUri) as? ProxyParseResult.Success)?.profile?.name
            if (editor.profileId == null) {
                val active = proxyProfileRepository.getSelected()
                val becameActive = !hadActiveRoute && active != null && active.rawUri.trim() == editor.rawUri.trim()
                notify("Added ${name ?: "the route"}" + if (becameActive) " · set as active" else "")
            } else {
                notify(
                    text = "Saved ${name ?: existing?.name.orEmpty()}".trim(),
                    detail = if (existing?.source == ProxyProfileSource.REMOTE) {
                        "It’s your route now, so a refresh won’t change it."
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun selectProfile(id: String) {
        if (selectedIds.value.isNotEmpty() || selectionActive.value) {
            toggleBulkSelection(id)
            return
        }
        viewModelScope.launch { runStorage { proxyProfileRepository.select(id) } }
    }

    private fun toggleBulkSelection(id: String) {
        selectedIds.update { selected -> if (id in selected) selected - id else selected + id }
    }

    /**
     * Makes route [id] the active one and, when this ViewModel's own Routes session is running
     * ([OpenSourceUiState.xrayConnected]: an Open Source Xray session that is connecting, connected
     * or reconnecting) through a different route, restarts that session through [id] — the same
     * stop → wait for the Xray transport to go away (≤ 2 s) → connect sequence that re-applies the
     * app-routing settings.
     *
     * - Unlike [selectProfile] it ignores bulk selection: it always selects the route.
     * - Without an active Routes session it only selects the route; Auto and Server sessions are
     *   never touched (switching modes is the caller's "Stop & switch" flow).
     * - A restart already under way (settings change or an earlier switch) finishes first; the
     *   route is re-checked afterwards, so the last switch wins.
     * - If "Only selected apps" has no apps, the session is left running and the
     *   no-selected-apps dialog is raised instead.
     * - An outdated route can't connect: it is selected, but the running session is left alone.
     * - A failed reconnect is published as an error on route [id] (shown like a failed Connect).
     */
    fun switchToProfile(id: String) {
        viewModelScope.launch {
            if (!runStorage { proxyProfileRepository.select(id) }) return@launch
            restartSessionThroughRoute(id)
        }
    }

    /** Turns multi-select on with nothing selected yet ("Press and hold to select several"). */
    fun beginSelection() {
        selectionActive.value = true
    }

    fun beginBulkSelection(id: String) {
        selectionActive.value = true
        selectedIds.value = selectedIds.value + id
    }

    fun selectAll() {
        selectedIds.value = uiState.value.profiles
            .filterNot(ProxyProfileSummary::isPinned)
            .mapTo(linkedSetOf(), ProxyProfileSummary::id)
    }

    fun clearSelection() {
        selectionActive.value = false
        selectedIds.value = emptySet()
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            val before = uiState.value.activeProfile
            val name = before?.takeIf { it.id == id }?.name
                ?: runCatching { proxyProfileRepository.getById(id)?.name }.getOrNull()
                ?: "Route"
            try {
                proxyProfileRepository.delete(setOf(id))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                notify("Couldn’t delete $name. Nothing was changed, try again.", tone = RoutesNoticeTone.Error)
                return@launch
            }
            notify("$name deleted", detail = activeTakeoverDetail(before?.id, removedIds = setOf(id)))
        }
    }

    fun deleteSelected() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val before = uiState.value.activeProfile?.id
            val count = ids.size
            try {
                proxyProfileRepository.delete(ids)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                notify(
                    "Couldn’t delete $count ${plural(count, "route", "routes")}. Nothing was changed, try again.",
                    tone = RoutesNoticeTone.Error,
                )
                return@launch
            }
            clearSelection()
            notify(
                "Deleted $count ${plural(count, "route", "routes")}",
                detail = activeTakeoverDetail(before, removedIds = ids),
            )
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
            val before = uiState.value.activeProfile?.id
            operation.update {
                it.copy(isRemovingUnavailable = true, message = null, notice = null)
            }
            try {
                val removed = proxyProfileRepository.deleteUnavailableExceptPinned()
                operation.update { it.copy(isRemovingUnavailable = false) }
                if (removed == 0) {
                    notify("No unavailable routes to remove", tone = RoutesNoticeTone.Info)
                } else {
                    val takeover = activeTakeoverDetail(before, removedIds = null)
                    notify(
                        "Removed $removed unavailable ${plural(removed, "route", "routes")}",
                        detail = takeover ?: "Pinned routes were kept.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notify(
                    "Couldn’t remove unavailable routes",
                    detail = (error.message ?: "Storage error") + ". Nothing was changed, try again.",
                    tone = RoutesNoticeTone.Error,
                )
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
            runStorage { proxyProfileRepository.setPinned(id, pinned) }
        }
    }

    suspend fun rawUri(id: String): String = proxyProfileRepository.getById(id)?.rawUri.orEmpty()

    /** When route [id] was last checked (epoch millis), or null if never. */
    suspend fun lastCheckedAt(id: String): Long? =
        runCatching { proxyProfileRepository.getById(id)?.lastTestAt }.getOrNull()

    fun connect() {
        if (operation.value.isChecking || checkJob?.isCompleted == false) {
            notify("Cancel the route check before connecting", tone = RoutesNoticeTone.Info)
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
                    uiState.value.activeProfile?.id ?: uiState.value.selectedProfile?.id,
                    error.message ?: "Unknown connection error",
                )
            }
        }
    }

    /**
     * Connect from the route library: Routes becomes the mode Home shows, then [connect] (which
     * stops a running Server or Auto session first).
     */
    fun connectFromLibrary() {
        appSettingsRepository.setActiveGlobalTab(GlobalTab.OPEN_SOURCE)
        connect()
    }

    /** Makes route [id] active and connects through it (the tablet's "Connect" / "Switch to this route"). */
    fun connectThrough(id: String) {
        viewModelScope.launch {
            if (runStorage { proxyProfileRepository.select(id) }) connectFromLibrary()
        }
    }

    /** "Stop & connect" while routes are being checked: cancels the check, waits for it, connects. */
    fun stopChecksAndConnect() {
        viewModelScope.launch {
            checkJob?.cancelAndJoin()
            connectFromLibrary()
        }
    }

    fun disconnect() {
        disconnectVpnUseCase()
    }

    fun dismissNoSelectedAppsDialog() {
        showNoSelectedAppsDialog.value = false
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

    /** Checks the active route (Home's "Check route"); reported like the library's single-route check. */
    fun checkAll() {
        // A full Xray batch probe supersedes the old duplicate TCP endpoint phase and keeps
        // hundreds of profiles within a short, bounded foreground operation. Use the complete
        // repository-backed ID set so an active search/filter cannot silently skip profiles.
        runChecks(uiState.value.allProfileIds.toList(), pingEndpoints = false)
    }

    /** Checks one route (the route sheet's "Check this route", the tablet's "Check route", Home's "Check route"). */
    fun checkRoute(id: String) {
        runChecks(listOf(id), pingEndpoints = false, singleRouteId = id)
    }

    /** Cancels a running check; the cancellation notice ("Check cancelled at x/y") follows. */
    fun cancelChecks() {
        checkJob?.takeIf(Job::isActive)?.cancel()
    }

    fun clearMessage() {
        operation.update { it.copy(message = null, notice = null) }
    }

    fun checkXrayCoreUpdates() {
        if (xrayCoreUpdateState.value.isChecking) return
        viewModelScope.launch {
            xrayCoreUpdateState.update { it.copy(isChecking = true, statusMessage = null, statusKind = null) }
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
                        statusKind = if (runtimeAsset == null) {
                            XrayCoreStatusKind.NO_ASSET
                        } else {
                            XrayCoreStatusKind.RELEASE_FOUND
                        },
                    )
                }
            }.onFailure { error ->
                xrayCoreUpdateState.update {
                    it.copy(
                        isChecking = false,
                        statusMessage = error.message ?: "Unable to check Xray core updates",
                        statusKind = XrayCoreStatusKind.CHECK_FAILED,
                    )
                }
            }
        }
    }

    fun downloadXrayCore(asset: XrayCoreAsset) {
        val state = xrayCoreUpdateState.value
        if (xrayCoreDownloadJob?.isActive == true || state.isDownloading || state.isChecking) return
        if (xrayRuntimeInUse()) {
            xrayCoreUpdateState.update {
                it.copy(
                    statusMessage = "Disconnect the active Xray VPN before updating Xray core",
                    statusKind = XrayCoreStatusKind.DOWNLOAD_BLOCKED,
                )
            }
            return
        }
        if (asset.abi != xrayCoreUpdateRepository.runtimeAbi) {
            xrayCoreUpdateState.update {
                it.copy(
                    statusMessage = "Xray core ${asset.abi} is not compatible with runtime ABI " +
                        xrayCoreUpdateRepository.runtimeAbi,
                    statusKind = XrayCoreStatusKind.DOWNLOAD_BLOCKED,
                )
            }
            return
        }

        xrayCoreInstallInProgress.value = true
        xrayCoreDownloadJob = viewModelScope.launch {
            xrayCoreUpdateState.update {
                it.copy(
                    isDownloading = true,
                    downloadingAbi = asset.abi,
                    statusMessage = "Downloading Xray core for ${asset.abi}",
                    statusKind = XrayCoreStatusKind.DOWNLOADING,
                )
            }
            var downloadedFile: java.io.File? = null
            var installResult: XrayCoreInstallResult? = null
            runCatching {
                val file = xrayCoreUpdateRepository.download(asset)
                downloadedFile = file
                // Like the old Smart Connect download: a connection may have started meanwhile (the
                // quick tile, a restored session); never swap the engine under it.
                check(!xrayRuntimeInUse()) {
                    "An Xray VPN started during the download. Disconnect it and try installing again."
                }
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
                        statusKind = when (installResult) {
                            XrayCoreInstallResult.ALREADY_INSTALLED -> XrayCoreStatusKind.ALREADY_INSTALLED
                            XrayCoreInstallResult.INSTALLED_AFTER_RESTART -> XrayCoreStatusKind.INSTALLED_AFTER_RESTART
                            XrayCoreInstallResult.INSTALLED,
                            null -> XrayCoreStatusKind.INSTALLED
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
                        statusKind = if (error is CancellationException) {
                            XrayCoreStatusKind.CANCELLED
                        } else {
                            XrayCoreStatusKind.INSTALL_FAILED
                        },
                    )
                }
            }.also {
                xrayCoreDownloadJob = null
                xrayCoreInstallInProgress.value = false
            }
        }
    }

    /** Routes or Auto is using the engine: it can't be replaced now. */
    private fun xrayRuntimeInUse(): Boolean =
        vpnConnectionRepository.currentState.ownsXrayRuntime() || isAutoActive()

    fun cancelXrayCoreDownload() {
        xrayCoreDownloadJob?.cancel()
        xrayCoreDownloadJob = null
        xrayCoreInstallInProgress.value = false
        xrayCoreUpdateState.update {
            it.copy(
                isDownloading = false,
                downloadingAbi = null,
                statusMessage = "Xray core download cancelled",
                statusKind = XrayCoreStatusKind.CANCELLED,
            )
        }
    }

    private fun importText(text: String, source: ProxyProfileSource) {
        viewModelScope.launch {
            val result = try {
                proxyProfileRepository.import(text, source)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                notify("Couldn’t import routes. Nothing was changed, try again.", tone = RoutesNoticeTone.Error)
                return@launch
            }
            val skipped = importSkippedNotes(result)
            if (result.added == 0 && result.updated == 0) {
                notify(
                    "Nothing new to import",
                    detail = skipped.joinToString(" · ").ifEmpty { null },
                    tone = RoutesNoticeTone.Info,
                )
            } else {
                val imported = result.added + result.updated
                notify(
                    (listOf("Imported $imported ${plural(imported, "route", "routes")}") + skipped)
                        .joinToString(" · "),
                )
            }
        }
    }

    private fun nextSyncOutcome(failureMessage: String?): LibrarySyncOutcome {
        syncSequence += 1
        return LibrarySyncOutcome(syncSequence, failureMessage)
    }

    private fun notify(
        text: String,
        detail: String? = null,
        tone: RoutesNoticeTone = RoutesNoticeTone.Success,
    ) {
        noticeSequence += 1
        val notice = RoutesNotice(noticeSequence, text, detail, tone)
        operation.update {
            it.copy(notice = notice, message = listOfNotNull(text, detail).joinToString(". "))
        }
    }

    private fun updateEditorState(transform: (ProxyEditorState) -> ProxyEditorState) {
        dialogState.update { state -> state.copy(editor = state.editor?.let(transform)) }
    }

    /** Runs a storage write; a failure becomes an error notice instead of crashing. */
    private suspend fun runStorage(block: suspend () -> Unit): Boolean {
        return try {
            block()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            notify("Couldn’t save the change. Try again.", tone = RoutesNoticeTone.Error)
            false
        }
    }

    /**
     * "X is now active." when the active route changed (it was deleted, removed as unavailable or
     * outdated), "No active route left." when none remains, else null.
     */
    private suspend fun activeTakeoverDetail(activeBefore: String?, removedIds: Set<String>?): String? {
        val active = runCatching { proxyProfileRepository.getSelected() }.getOrNull()
        return when {
            active != null && active.id != activeBefore -> "${active.name} is now active."
            active == null && activeBefore != null &&
                (removedIds == null || activeBefore in removedIds) -> "No active route left."
            else -> null
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
                reconnectThroughSelectedProfile(failureProfileId = uiState.value.selectedProfile?.id)
            } finally {
                settingsReconnectStarted = false
            }
        }
    }

    /**
     * Restarts this ViewModel's Routes session through [profileId] (already selected) unless it is
     * idle, owned by another mode or already running through that route. Shares
     * [settingsReconnectJob] with settings-driven restarts so the two never run in parallel.
     */
    private suspend fun restartSessionThroughRoute(profileId: String) {
        // Never cancel a restart that already stopped the session; wait for every running one.
        while (true) {
            val running = settingsReconnectJob?.takeIf { job -> job.isActive && settingsReconnectStarted }
                ?: break
            running.join()
        }
        val state = vpnConnectionRepository.currentState
        if (!state.isXrayActive() || state.activeConfigId == profileId) return
        // Only current routes connect (`getSelected` skips outdated ones): restarting through an
        // outdated route would just drop the running session. Keep it; a newer switch re-checks.
        val selected = try {
            proxyProfileRepository.getSelected()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (selected?.id != profileId) return
        if (appSettingsRepository.settings.value.requiresSelectedAppsButHasNone()) {
            showNoSelectedAppsDialog.value = true
            return
        }
        // A settings restart still in its debounce is superseded: this one reads the latest settings.
        settingsReconnectJob?.cancel()
        settingsReconnectJob = viewModelScope.launch {
            settingsReconnectStarted = true
            try {
                reconnectThroughSelectedProfile(failureProfileId = profileId)
            } finally {
                settingsReconnectStarted = false
            }
        }
    }

    /**
     * Stops the running Xray session, waits up to [TRANSPORT_SWITCH_TIMEOUT_MS] for the transport to
     * be released and connects through the currently selected route. A start failure is published
     * as an error on [failureProfileId].
     */
    private suspend fun reconnectThroughSelectedProfile(failureProfileId: String?) {
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
            if (error is CancellationException) throw error
            vpnConnectionRepository.setError(
                failureProfileId,
                error.message ?: "Unknown connection error",
            )
        }
    }

    private fun runChecks(profileIds: List<String>, pingEndpoints: Boolean, singleRouteId: String? = null) {
        val distinctProfileIds = profileIds.distinct()
        if (checkJob?.isCompleted == false) return
        if (vpnConnectionRepository.currentState.ownsXrayRuntime()) {
            notify(
                "Disconnect to run checks",
                detail = "Routes can’t be tested while a connection uses the Xray engine.",
                tone = RoutesNoticeTone.Locked,
            )
            return
        }
        if (distinctProfileIds.isEmpty()) {
            notify("No routes to check", tone = RoutesNoticeTone.Info)
            return
        }
        lateinit var launchedJob: Job
        launchedJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var completedNormally = false
            val checksStartedAtNanos = System.nanoTime()
            val checksDeadlineNanos = checksStartedAtNanos +
                XRAY_BATCH_TOTAL_BUDGET_MS * NANOS_IN_MILLIS
            try {
                // The whole library: a search or filter on the Route library must not hide a checked
                // route's details (its transport, fingerprint and name), e.g. Home's active route.
                val summariesById = uiState.value.library.associateBy(ProxyProfileSummary::id)
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
                        checkingRouteId = singleRouteId,
                        checkAvailableSoFar = 0,
                        notice = null,
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
                val elapsedMs = (System.nanoTime() - checksStartedAtNanos) / NANOS_IN_MILLIS
                completedNormally = true
                operation.update {
                    it.copy(
                        isChecking = false,
                        checkingRouteId = null,
                        checkCompleted = totalWork,
                        checkPhase = null,
                        checkPhaseCompleted = 0,
                        checkPhaseTotal = 0,
                    )
                }
                if (pingEndpoints) {
                    notify(
                        "Check finished",
                        detail = "Endpoints answered for $pinged of $endpointPingProfileCount numeric-IP routes · " +
                            checkSummary(tunnelResults),
                    )
                } else if (singleRouteId != null) {
                    val name = summariesById[singleRouteId]?.name ?: "The route"
                    singleCheckNotice(name, tunnelResults.firstOrNull { it.profileId == singleRouteId })
                } else {
                    notify(
                        "Check finished in ${formatSeconds(elapsedMs)}",
                        detail = checkSummary(tunnelResults),
                    )
                }
            } catch (error: XrayRuntimeBusyException) {
                val state = operation.value
                notify(
                    "Check stopped at ${state.checkCompleted}/${state.checkTotal}",
                    detail = error.message,
                    tone = RoutesNoticeTone.Locked,
                )
            } catch (error: CancellationException) {
                val state = operation.value
                notify(
                    "Check cancelled at ${state.checkCompleted}/${state.checkTotal}",
                    detail = "Results from earlier checks are kept.",
                    tone = RoutesNoticeTone.Info,
                )
                throw error
            } catch (error: Throwable) {
                notify(
                    "Check failed",
                    detail = error.message ?: "The route check stopped unexpectedly. Try again.",
                    tone = RoutesNoticeTone.Error,
                )
            } finally {
                if (!completedNormally) {
                    operation.update {
                        it.copy(
                            isChecking = false,
                            checkingRouteId = null,
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
        val availableTunnels = AtomicInteger(0)
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
                    availableSoFar = availableTunnels.get(),
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
                onResult = { result ->
                    if (result.status == ProxyTestStatus.AVAILABLE) availableTunnels.incrementAndGet()
                    publishCompleted(completedTunnels.incrementAndGet())
                },
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
                availableSoFar = availableTunnels.get(),
            )
        }
    }

    private fun publishCheckProgress(
        phase: ProxyCheckPhase,
        phaseCompleted: Int,
        phaseTotal: Int,
        overallOffset: Int,
        hostPingUpdates: Map<String, Long> = emptyMap(),
        availableSoFar: Int? = null,
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
                    checkAvailableSoFar = maxOf(state.checkAvailableSoFar, availableSoFar ?: 0),
                )
            }
        }
    }

    private fun singleCheckNotice(name: String, result: ProxyTunnelTestResult?) {
        when (result?.status) {
            ProxyTestStatus.AVAILABLE -> notify(
                "$name works",
                detail = result.latencyMs?.let { "Answered in $it ms through the Xray engine." }
                    ?: "It answered through the Xray engine.",
            )
            ProxyTestStatus.UNSUPPORTED -> notify(
                "$name isn’t supported",
                detail = "The Xray engine rejected this route’s settings.",
                tone = RoutesNoticeTone.Error,
            )
            ProxyTestStatus.UNAVAILABLE -> notify(
                "$name didn’t answer",
                detail = "No reply within 5 s. Try later or pick another route.",
                tone = RoutesNoticeTone.Error,
            )
            else -> notify(
                "$name wasn’t checked",
                detail = "The check ran out of time. Try again.",
                tone = RoutesNoticeTone.Info,
            )
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
    val lastSync: LibrarySyncOutcome? = null,
    val isRemovingUnavailable: Boolean = false,
    val isChecking: Boolean = false,
    val checkCompleted: Int = 0,
    val checkTotal: Int = 0,
    val checkPhase: ProxyCheckPhase? = null,
    val checkPhaseCompleted: Int = 0,
    val checkPhaseTotal: Int = 0,
    val hostPingMs: Map<String, Long> = emptyMap(),
    val message: String? = null,
    val notice: RoutesNotice? = null,
    val checkingRouteId: String? = null,
    val checkAvailableSoFar: Int = 0,
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
    val statusFilter: RouteStatusFilter,
    val selectedIds: Set<String>,
    val selectionActive: Boolean,
    val unavailableUnpinnedCount: Int,
    val counts: RouteCounts,
    val activeProfile: ProxyProfileSummary?,
    val library: List<ProxyProfileSummary>,
)

private data class SelectionState(
    val ids: Set<String>,
    val active: Boolean,
)

private data class AuxiliaryState(
    val operation: OperationState,
    val dialogs: DialogState,
    val vpnState: VpnConnectionState,
    val showNoSelectedAppsDialog: Boolean,
    val xrayCoreAvailable: Boolean,
)

/** Available on the last check and not outdated (the "Available" chip and count). */
internal fun ProxyProfileSummary.isAvailableRoute(): Boolean =
    lastTestStatus == ProxyTestStatus.AVAILABLE && !isStale

private fun ProxyProfileSummary.matchesStatus(filter: RouteStatusFilter): Boolean = when (filter) {
    RouteStatusFilter.ALL -> true
    RouteStatusFilter.AVAILABLE -> isAvailableRoute()
    RouteStatusFilter.PINNED -> isPinned
    RouteStatusFilter.NOT_CHECKED -> lastTestStatus == ProxyTestStatus.NOT_TESTED
}

internal fun routeCounts(profiles: List<ProxyProfileSummary>): RouteCounts {
    var available = 0
    var unavailable = 0
    var unsupported = 0
    var notChecked = 0
    var pinned = 0
    profiles.forEach { profile ->
        if (profile.isPinned) pinned += 1
        when {
            profile.isAvailableRoute() -> available += 1
            profile.lastTestStatus == ProxyTestStatus.UNAVAILABLE -> unavailable += 1
            profile.lastTestStatus == ProxyTestStatus.UNSUPPORTED -> unsupported += 1
            profile.lastTestStatus == ProxyTestStatus.NOT_TESTED -> notChecked += 1
        }
    }
    return RouteCounts(profiles.size, available, unavailable, unsupported, notChecked, pinned)
}

internal fun plural(count: Int, one: String, many: String): String = if (count == 1) one else many

/** "0 new · 120 updated · 3 duplicates · 1 invalid" (+ unsupported when there are any). */
internal fun importSummary(result: ProxyImportResult): String = buildList {
    add("${result.added} new")
    add("${result.updated} updated")
    add("${result.duplicates} ${plural(result.duplicates, "duplicate", "duplicates")}")
    add("${result.invalid} invalid")
    if (result.unsupported > 0) add("${result.unsupported} unsupported")
}.joinToString(" · ")

/**
 * "2 duplicates skipped", "1 couldn't be read", "1 not supported": the lines an import left out. A saved
 * route with a transport the engine doesn't know isn't one of them, so it isn't called "not supported".
 */
internal fun importSkippedNotes(result: ProxyImportResult): List<String> = buildList {
    if (result.duplicates > 0) {
        add("${result.duplicates} ${plural(result.duplicates, "duplicate", "duplicates")} skipped")
    }
    if (result.invalid > 0) add("${result.invalid} couldn't be read")
    if (result.unsupportedSkipped > 0) add("${result.unsupportedSkipped} not supported")
}

/** Why saving the editor failed ([editing]: an existing route, [routeExists]: still in the library), or null. */
internal fun editorSaveError(result: ProxyImportResult, editing: Boolean, routeExists: Boolean): String? = when {
    result.duplicates > 0 -> "This link is already in your library. Change it or cancel."
    editing && result.invalid > 0 && !routeExists -> "This route was deleted, so the change can’t be saved."
    // A known link the engine can't run (Hysteria v1, say) is never saved.
    result.unsupportedSkipped > 0 -> "This link type isn’t supported."
    result.invalid > 0 -> "This link can’t be read. Check it and try again."
    else -> null
}

/** "41 available · 63 unavailable · 9 unsupported · 15 timed out". */
internal fun checkSummary(results: List<ProxyTunnelTestResult>): String {
    val available = results.count { it.status == ProxyTestStatus.AVAILABLE }
    val unavailable = results.count { it.status == ProxyTestStatus.UNAVAILABLE }
    val unsupported = results.count { it.status == ProxyTestStatus.UNSUPPORTED }
    val notTested = results.count { it.status == ProxyTestStatus.NOT_TESTED }
    return buildList {
        add("$available available")
        add("$unavailable unavailable")
        add("$unsupported unsupported")
        if (notTested > 0) add("$notTested timed out")
    }.joinToString(" · ")
}

/** 9412 → "9.4 s". */
internal fun formatSeconds(millis: Long): String = String.format(Locale.US, "%.1f s", millis / 1_000.0)

/** Library search: name, host, protocol (its name, link scheme or alias such as `hy2`), transport or security. */
internal fun ProxyProfileSummary.matchesNormalized(query: String): Boolean =
    name.contains(query, ignoreCase = true) ||
        host.contains(query, ignoreCase = true) ||
        protocol.name.contains(query, ignoreCase = true) ||
        protocol.scheme.contains(query, ignoreCase = true) ||
        protocol.aliases.any { alias -> alias.contains(query, ignoreCase = true) } ||
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
        sessionOwner == VpnSessionOwner.OPEN_SOURCE &&
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

private const val EDITOR_STORAGE_ERROR = "Couldn’t save the route. Nothing was changed, try again."
private const val SETTINGS_CHANGE_DEBOUNCE_MS = 250L
private const val PROFILE_SEARCH_DEBOUNCE_MS = 200L
private const val HOST_PING_TIMEOUT_MS = 1_500
private const val HOST_PING_CONCURRENCY = 12
// A live 10 Hz-ish counter is visually continuous while avoiding hundreds of Compose updates.
private const val MAX_PROGRESS_PUBLICATIONS_PER_PHASE = 100
private const val PROGRESS_MAX_SILENCE_NANOS = 100_000_000L
private const val NANOS_IN_MILLIS = 1_000_000L
private const val TRANSPORT_SWITCH_TIMEOUT_MS = 2_000L
