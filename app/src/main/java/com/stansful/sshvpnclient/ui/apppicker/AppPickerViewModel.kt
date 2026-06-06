package com.stansful.sshvpnclient.ui.apppicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.InstalledAppInfo
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.InstalledAppsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
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

class AppPickerViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val installedAppsRepository: InstalledAppsRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val selectedPackages = MutableStateFlow(appSettingsRepository.settings.value.selectedAppPackages)
    private val isLoading = MutableStateFlow(true)

    val uiState = combine(
        query,
        installedApps,
        selectedPackages,
        isLoading,
    ) { query, apps, selectedPackages, loading ->
        val normalizedQuery = query.trim()
        val filteredApps = if (normalizedQuery.isBlank()) {
            apps
        } else {
            apps.filter { app ->
                app.label.contains(normalizedQuery, ignoreCase = true) ||
                    app.packageName.contains(normalizedQuery, ignoreCase = true)
            }
        }

        AppPickerUiState(
            query = query,
            apps = filteredApps,
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
            installedApps.value = installedAppsRepository.getInstalledApps()
            isLoading.value = false
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
}
