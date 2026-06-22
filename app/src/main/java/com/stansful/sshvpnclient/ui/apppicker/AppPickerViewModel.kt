package com.stansful.sshvpnclient.ui.apppicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.InstalledAppInfo
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.InstalledAppsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppPickerUiState(
    val query: String = "",
    val apps: List<InstalledAppInfo> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val isLoading: Boolean = true,
) {
    val selectedCount: Int
        get() = selectedPackages.size
}

@OptIn(FlowPreview::class)
class AppPickerViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val installedAppsRepository: InstalledAppsRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val selectedPackages = MutableStateFlow(appSettingsRepository.settings.value.selectedAppPackages)
    private val isLoading = MutableStateFlow(true)
    private val filteredApps = combine(
        query.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged(),
        installedApps,
    ) { query, apps ->
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            apps
        } else {
            apps.filter { app ->
                app.label.contains(normalizedQuery, ignoreCase = true) ||
                    app.packageName.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }.flowOn(Dispatchers.Default)

    val uiState = combine(
        query,
        filteredApps,
        selectedPackages,
        isLoading,
    ) { query, apps, selectedPackages, loading ->
        AppPickerUiState(
            query = query,
            apps = apps,
            selectedPackages = selectedPackages,
            isLoading = loading,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppPickerUiState(),
    )

    init {
        viewModelScope.launch {
            try {
                installedApps.value = runCatching {
                    installedAppsRepository.getInstalledApps()
                }.getOrDefault(emptyList())
            } finally {
                isLoading.value = false
            }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun togglePackage(packageName: String) {
        val current = selectedPackages.value
        val next = if (packageName in current) {
            current - packageName
        } else {
            current + packageName
        }
        selectedPackages.value = next
    }

    fun saveSelection() {
        appSettingsRepository.setSelectedAppPackages(selectedPackages.value)
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 200L
    }
}
