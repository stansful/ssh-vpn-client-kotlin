package com.stansful.sshvpnclient.ui.apppicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.InstalledAppInfo
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.InstalledAppsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The installed-apps list as App routing groups it. */
data class AppPickerList(
    /** Installed apps of the "Selected" group (checked when the list opened), filtered. */
    val selected: List<InstalledAppInfo> = emptyList(),
    /** Saved packages that are no longer installed (still counted, skipped on connect), filtered. */
    val uninstalled: List<String> = emptyList(),
    /** Everything else that is visible and matches the search. */
    val others: List<InstalledAppInfo> = emptyList(),
    /** System apps that match the search but are hidden. */
    val hiddenSystemMatches: Int = 0,
)

data class AppPickerUiState(
    val query: String = "",
    val showSystemApps: Boolean = false,
    val selectedPackages: Set<String> = emptySet(),
    val installedPackages: Set<String> = emptySet(),
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val list: AppPickerList = AppPickerList(),
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val vpnMode: VpnMode = VpnMode.PROXY,
    /** "Only selected apps" was picked without an installed app: it turns on with the first one. */
    val pendingSelectedApps: Boolean = false,
    /** The saved list is open for editing while All apps stays on. */
    val editingWhileAllApps: Boolean = false,
) {
    val selectedCount: Int get() = selectedPackages.size

    /** Checked packages that are installed (only those can be routed). */
    val usableCount: Int
        get() = if (isLoading || loadFailed) {
            selectedPackages.size
        } else {
            selectedPackages.count { it in installedPackages }
        }
}

/** Something App routing tells the user about. */
sealed interface AppRoutingEvent {
    /** Only selected apps switched on by itself with the first picked app. */
    data object SelectedAppsOn : AppRoutingEvent

    /** A change that an active VPN applies by reconnecting (mode, or the list in Only selected apps). */
    data object RoutingChanged : AppRoutingEvent
}

/**
 * App routing: "All apps" or "Only selected apps" plus the app list. Edits apply right away, one write
 * per pause in tapping (the VPN's own view models reconnect once per write). "Only selected apps" is only
 * saved once the list has an installed app; until then the choice is pending and All apps stays on.
 * While it is on, unchecking every app is held back (the running VPN keeps the old list) until the
 * screen closes, which then saves the empty list as the old picker did.
 */
@OptIn(FlowPreview::class)
class AppPickerViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val installedAppsRepository: InstalledAppsRepository,
) : ViewModel() {
    private val session = MutableStateFlow(Session(selected = appSettingsRepository.settings.value.selectedAppPackages))
    private val apps = MutableStateFlow<AppsLoad>(AppsLoad.Loading)
    private val mutableEvents = MutableSharedFlow<AppRoutingEvent>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var sessionStarted = false
    private var commitJob: Job? = null
    private var loadJob: Job? = null

    val events: SharedFlow<AppRoutingEvent> = mutableEvents.asSharedFlow()

    private val searchQuery = session
        .map { it.query.trim() }
        .distinctUntilChanged()
        .debounce(SEARCH_DEBOUNCE_MS)
        .onStart { emit(session.value.query.trim()) }
        .distinctUntilChanged()

    val uiState: StateFlow<AppPickerUiState> = combine(
        session,
        searchQuery,
        apps,
        appSettingsRepository.settings,
    ) { session, query, load, settings ->
        val installed = (load as? AppsLoad.Loaded)?.apps.orEmpty()
        val installedPackages = installed.mapTo(HashSet()) { it.packageName }
        AppPickerUiState(
            query = session.query,
            showSystemApps = session.showSystem,
            selectedPackages = session.selected,
            installedPackages = installedPackages,
            installedApps = installed,
            list = if (load is AppsLoad.Loaded) {
                buildAppPickerList(
                    apps = installed,
                    installedPackages = installedPackages,
                    selected = session.selected,
                    group = session.group,
                    query = query,
                    showSystem = session.showSystem,
                )
            } else {
                AppPickerList()
            },
            isLoading = load == AppsLoad.Loading,
            loadFailed = load == AppsLoad.Failed,
            vpnMode = settings.vpnMode,
            pendingSelectedApps = session.pending,
            editingWhileAllApps = session.editingWhileAllApps,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppPickerUiState(
            selectedPackages = session.value.selected,
            vpnMode = appSettingsRepository.settings.value.vpnMode,
        ),
    )

    init {
        loadApps()
    }

    /** Reloads the installed apps after a failure. */
    fun retryLoading() = loadApps()

    fun setQuery(value: String) {
        session.update { it.copy(query = value) }
    }

    fun setShowSystemApps(show: Boolean) {
        session.update { it.copy(showSystem = show) }
    }

    /** Starts the visit once per screen (a rotation keeps the edits and the pending choice). */
    fun startSession() {
        if (!sessionStarted) refreshSelection()
    }

    /**
     * Starts a visit: re-reads the saved list so a change made elsewhere is not overwritten by an old
     * snapshot, and fixes the "Selected" group so rows never jump under the finger.
     */
    fun refreshSelection() {
        val saved = appSettingsRepository.settings.value.selectedAppPackages
        session.update { it.copy(selected = saved, group = saved, pending = false, editingWhileAllApps = false) }
        sessionStarted = true
    }

    fun togglePackage(packageName: String) {
        if (!sessionStarted) refreshSelection()
        session.update { current ->
            val next = if (packageName in current.selected) {
                current.selected - packageName
            } else {
                current.selected + packageName
            }
            current.copy(selected = next)
        }
        val pendingWithApp = session.value.pending && usable(session.value.selected) > 0
        if (pendingWithApp) commitNow() else scheduleCommit()
    }

    fun chooseAllApps() {
        if (!sessionStarted) refreshSelection()
        commitJob?.cancel()
        val current = session.value
        session.update { it.copy(pending = false, editingWhileAllApps = false, query = "") }
        writeList(current.selected, allowEmpty = true)
        if (current.pending || appSettingsRepository.settings.value.vpnMode == VpnMode.PROXY) return
        appSettingsRepository.setVpnMode(VpnMode.PROXY)
        mutableEvents.tryEmit(AppRoutingEvent.RoutingChanged)
    }

    fun chooseSelectedApps() {
        if (!sessionStarted) refreshSelection()
        val current = session.value
        if (current.pending || appSettingsRepository.settings.value.vpnMode == VpnMode.SELECTED_APPS) return
        commitJob?.cancel()
        // The list is written while All apps is still on, so only the mode change reconnects.
        writeList(current.selected, allowEmpty = true)
        val usable = usable(current.selected)
        session.update {
            it.copy(
                pending = usable == 0,
                editingWhileAllApps = false,
                group = if (current.editingWhileAllApps) it.group else it.selected,
            )
        }
        if (usable > 0) {
            appSettingsRepository.setVpnMode(VpnMode.SELECTED_APPS)
            mutableEvents.tryEmit(AppRoutingEvent.RoutingChanged)
        }
    }

    /** Opens the saved list for editing while All apps stays on (nothing reconnects). */
    fun editListWhileAllApps() {
        if (!sessionStarted) refreshSelection()
        session.update { it.copy(editingWhileAllApps = true, query = "", group = it.selected) }
    }

    fun finishEditingList() {
        commitNow()
        session.update { it.copy(editingWhileAllApps = false, query = "") }
    }

    /** Leaving the screen: writes what is still pending, the empty list included. */
    fun saveSelection() {
        // A Back event can theoretically beat the first LaunchedEffect frame. In that case do
        // nothing instead of writing the ViewModel's snapshot from a previous picker visit.
        if (!sessionStarted) return
        commitJob?.cancel()
        commit(final = true)
        sessionStarted = false
    }

    override fun onCleared() {
        saveSelection()
    }

    private fun scheduleCommit() {
        commitJob?.cancel()
        commitJob = viewModelScope.launch {
            delay(COMMIT_DEBOUNCE_MS)
            commit(final = false)
        }
    }

    private fun commitNow() {
        commitJob?.cancel()
        commit(final = false)
    }

    private fun commit(final: Boolean) {
        val current = session.value
        val usable = usable(current.selected)
        val selectedMode = appSettingsRepository.settings.value.vpnMode == VpnMode.SELECTED_APPS
        when {
            current.pending && usable > 0 -> {
                writeList(current.selected, allowEmpty = false)
                appSettingsRepository.setVpnMode(VpnMode.SELECTED_APPS)
                session.update { it.copy(pending = false) }
                mutableEvents.tryEmit(AppRoutingEvent.SelectedAppsOn)
                mutableEvents.tryEmit(AppRoutingEvent.RoutingChanged)
            }
            current.pending -> if (final) writeList(current.selected, allowEmpty = true)
            selectedMode -> if (usable > 0 || final) {
                if (writeList(current.selected, allowEmpty = true)) {
                    mutableEvents.tryEmit(AppRoutingEvent.RoutingChanged)
                }
            }
            else -> writeList(current.selected, allowEmpty = true)
        }
    }

    /** Saves [packages] when they differ from the saved list; true when something was written. */
    private fun writeList(packages: Set<String>, allowEmpty: Boolean): Boolean {
        if (!allowEmpty && packages.isEmpty()) return false
        if (packages == appSettingsRepository.settings.value.selectedAppPackages) return false
        appSettingsRepository.setSelectedAppPackages(packages)
        return true
    }

    private fun usable(packages: Set<String>): Int {
        val load = apps.value as? AppsLoad.Loaded ?: return packages.size
        val installed = load.apps.mapTo(HashSet()) { it.packageName }
        return packages.count { it in installed }
    }

    private fun loadApps() {
        loadJob?.cancel()
        apps.value = AppsLoad.Loading
        loadJob = viewModelScope.launch {
            apps.value = try {
                AppsLoad.Loaded(installedAppsRepository.getInstalledApps())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                AppsLoad.Failed
            }
        }
    }

    private data class Session(
        val selected: Set<String>,
        val group: Set<String> = selected,
        val query: String = "",
        val showSystem: Boolean = false,
        val pending: Boolean = false,
        val editingWhileAllApps: Boolean = false,
    )

    private sealed interface AppsLoad {
        data object Loading : AppsLoad
        data object Failed : AppsLoad
        data class Loaded(val apps: List<InstalledAppInfo>) : AppsLoad
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 200L
        const val COMMIT_DEBOUNCE_MS = 600L
    }
}

/**
 * Groups [apps] for App routing. System apps stay hidden unless [showSystem] or they are checked or in
 * the [group]; the search matches app names and package names (case-insensitive). Uninstalled saved
 * packages also match "uninstalled app".
 */
fun buildAppPickerList(
    apps: List<InstalledAppInfo>,
    installedPackages: Set<String>,
    selected: Set<String>,
    group: Set<String>,
    query: String,
    showSystem: Boolean,
): AppPickerList {
    val needle = query.trim()
    fun matches(app: InstalledAppInfo) = needle.isEmpty() ||
        app.label.contains(needle, ignoreCase = true) ||
        app.packageName.contains(needle, ignoreCase = true)
    fun visible(app: InstalledAppInfo) =
        !app.isSystem || showSystem || app.packageName in selected || app.packageName in group
    val shown = apps.filter { visible(it) && matches(it) }
    val uninstalled = (group + selected)
        .filter { it !in installedPackages }
        .filter {
            needle.isEmpty() ||
                it.contains(needle, ignoreCase = true) ||
                UNINSTALLED_LABEL.contains(needle, ignoreCase = true)
        }
        .sorted()
    return AppPickerList(
        selected = shown.filter { it.packageName in group },
        uninstalled = uninstalled,
        others = shown.filter { it.packageName !in group },
        hiddenSystemMatches = apps.count { !visible(it) && matches(it) },
    )
}

private const val UNINSTALLED_LABEL = "Uninstalled app"
