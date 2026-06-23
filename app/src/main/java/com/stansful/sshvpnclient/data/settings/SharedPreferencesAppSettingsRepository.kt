package com.stansful.sshvpnclient.data.settings

import android.content.Context
import androidx.core.content.edit
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SharedPreferencesAppSettingsRepository(
    context: Context,
) : AppSettingsRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private val currentSettings = MutableStateFlow(readSettings())

    override val settings: StateFlow<AppSettings> = currentSettings.asStateFlow()

    override fun setShowLogsOnMain(show: Boolean) {
        preferences.edit {
            putBoolean(KEY_SHOW_LOGS_ON_MAIN, show)
        }
        currentSettings.value = currentSettings.value.copy(showLogsOnMain = show)
    }

    override fun setShowLogsOnOpenSource(show: Boolean) {
        preferences.edit {
            putBoolean(KEY_SHOW_LOGS_ON_OPEN_SOURCE, show)
        }
        currentSettings.value = currentSettings.value.copy(showLogsOnOpenSource = show)
    }

    override fun setShowTerminalOnMain(show: Boolean) {
        preferences.edit {
            putBoolean(KEY_SHOW_TERMINAL_ON_MAIN, show)
        }
        currentSettings.value = currentSettings.value.copy(showTerminalOnMain = show)
    }

    override fun setThemeMode(themeMode: AppThemeMode) {
        preferences.edit {
            putString(KEY_THEME_MODE, themeMode.storageValue)
        }
        currentSettings.value = currentSettings.value.copy(themeMode = themeMode)
    }

    override fun setCustomThemeColors(colors: CustomThemeColors) {
        preferences.edit {
            putInt(KEY_CUSTOM_PRIMARY, colors.primary)
            putInt(KEY_CUSTOM_SECONDARY, colors.secondary)
            putInt(KEY_CUSTOM_BACKGROUND, colors.background)
            putInt(KEY_CUSTOM_SURFACE, colors.surface)
            putInt(KEY_CUSTOM_SURFACE_VARIANT, colors.surfaceVariant)
            putInt(KEY_CUSTOM_ON_SURFACE, colors.onSurface)
            putInt(KEY_CUSTOM_OUTLINE, colors.outline)
            putInt(KEY_CUSTOM_ERROR, colors.error)
        }
        currentSettings.value = currentSettings.value.copy(customThemeColors = colors)
    }

    override fun setVpnMode(vpnMode: VpnMode) {
        preferences.edit {
            putString(KEY_VPN_MODE, vpnMode.storageValue)
        }
        currentSettings.value = currentSettings.value.copy(vpnMode = vpnMode)
    }

    override fun setSelectedAppPackages(packageNames: Set<String>) {
        val sortedPackages = packageNames.toSortedSet()
        preferences.edit {
            putStringSet(KEY_SELECTED_APP_PACKAGES, sortedPackages)
        }
        currentSettings.value = currentSettings.value.copy(selectedAppPackages = sortedPackages)
    }

    override fun setActiveGlobalTab(tab: GlobalTab) {
        preferences.edit { putString(KEY_ACTIVE_GLOBAL_TAB, tab.storageValue) }
        currentSettings.value = currentSettings.value.copy(activeGlobalTab = tab)
    }

    override fun setOpenSourceConsentVersion(version: Int) {
        preferences.edit { putInt(KEY_OPEN_SOURCE_CONSENT_VERSION, version) }
        currentSettings.value = currentSettings.value.copy(openSourceConsentVersion = version)
    }

    override fun setShowOpenSourceWarningOnEnter(show: Boolean) {
        preferences.edit { putBoolean(KEY_SHOW_OPEN_SOURCE_WARNING_ON_ENTER, show) }
        currentSettings.value = currentSettings.value.copy(showOpenSourceWarningOnEnter = show)
    }

    override fun setOpenSourceRiskBannerExpanded(expanded: Boolean) {
        preferences.edit { putBoolean(KEY_OPEN_SOURCE_RISK_BANNER_EXPANDED, expanded) }
        currentSettings.value = currentSettings.value.copy(openSourceRiskBannerExpanded = expanded)
    }

    override fun setOpenSourceAutoUpdateEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_OPEN_SOURCE_AUTO_UPDATE_ENABLED, enabled) }
        currentSettings.value = currentSettings.value.copy(openSourceAutoUpdateEnabled = enabled)
    }

    private fun readSettings(): AppSettings {
        return AppSettings(
            showLogsOnMain = preferences.getBoolean(KEY_SHOW_LOGS_ON_MAIN, false),
            showLogsOnOpenSource = preferences.getBoolean(KEY_SHOW_LOGS_ON_OPEN_SOURCE, false),
            showTerminalOnMain = preferences.getBoolean(KEY_SHOW_TERMINAL_ON_MAIN, false),
            themeMode = AppThemeMode.fromStorageValue(preferences.getString(KEY_THEME_MODE, null)),
            customThemeColors = readCustomThemeColors(),
            vpnMode = VpnMode.fromStorageValue(preferences.getString(KEY_VPN_MODE, null)),
            selectedAppPackages = preferences
                .getStringSet(KEY_SELECTED_APP_PACKAGES, emptySet())
                .orEmpty()
                .toSortedSet(),
            activeGlobalTab = GlobalTab.fromStorageValue(
                preferences.getString(KEY_ACTIVE_GLOBAL_TAB, null),
            ),
            openSourceConsentVersion = preferences.getInt(KEY_OPEN_SOURCE_CONSENT_VERSION, 0),
            showOpenSourceWarningOnEnter = preferences.getBoolean(
                KEY_SHOW_OPEN_SOURCE_WARNING_ON_ENTER,
                true,
            ),
            openSourceRiskBannerExpanded = preferences.getBoolean(
                KEY_OPEN_SOURCE_RISK_BANNER_EXPANDED,
                true,
            ),
            openSourceAutoUpdateEnabled = preferences.getBoolean(
                KEY_OPEN_SOURCE_AUTO_UPDATE_ENABLED,
                true,
            ),
        )
    }

    private fun readCustomThemeColors(): CustomThemeColors {
        val defaultColors = CustomThemeColors.defaultLight()
        return CustomThemeColors(
            primary = preferences.getInt(KEY_CUSTOM_PRIMARY, defaultColors.primary),
            secondary = preferences.getInt(KEY_CUSTOM_SECONDARY, defaultColors.secondary),
            background = preferences.getInt(KEY_CUSTOM_BACKGROUND, defaultColors.background),
            surface = preferences.getInt(KEY_CUSTOM_SURFACE, defaultColors.surface),
            surfaceVariant = preferences.getInt(KEY_CUSTOM_SURFACE_VARIANT, defaultColors.surfaceVariant),
            onSurface = preferences.getInt(KEY_CUSTOM_ON_SURFACE, defaultColors.onSurface),
            outline = preferences.getInt(KEY_CUSTOM_OUTLINE, defaultColors.outline),
            error = preferences.getInt(KEY_CUSTOM_ERROR, defaultColors.error),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "ssh-vpn-client-settings"
        const val KEY_SHOW_LOGS_ON_MAIN = "show_logs_on_main"
        const val KEY_SHOW_LOGS_ON_OPEN_SOURCE = "show_logs_on_open_source"
        const val KEY_SHOW_TERMINAL_ON_MAIN = "show_terminal_on_main"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_CUSTOM_PRIMARY = "custom_primary"
        const val KEY_CUSTOM_SECONDARY = "custom_secondary"
        const val KEY_CUSTOM_BACKGROUND = "custom_background"
        const val KEY_CUSTOM_SURFACE = "custom_surface"
        const val KEY_CUSTOM_SURFACE_VARIANT = "custom_surface_variant"
        const val KEY_CUSTOM_ON_SURFACE = "custom_on_surface"
        const val KEY_CUSTOM_OUTLINE = "custom_outline"
        const val KEY_CUSTOM_ERROR = "custom_error"
        const val KEY_VPN_MODE = "vpn_mode"
        const val KEY_SELECTED_APP_PACKAGES = "selected_app_packages"
        const val KEY_ACTIVE_GLOBAL_TAB = "active_global_tab"
        const val KEY_OPEN_SOURCE_CONSENT_VERSION = "open_source_consent_version"
        const val KEY_SHOW_OPEN_SOURCE_WARNING_ON_ENTER = "show_open_source_warning_on_enter"
        const val KEY_OPEN_SOURCE_RISK_BANNER_EXPANDED = "open_source_risk_banner_expanded"
        const val KEY_OPEN_SOURCE_AUTO_UPDATE_ENABLED = "open_source_auto_update_enabled"
    }
}
