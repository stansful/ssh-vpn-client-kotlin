package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.VpnMode
import kotlinx.coroutines.flow.StateFlow

interface AppSettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setShowLogsOnMain(show: Boolean)

    fun setShowLogsOnOpenSource(show: Boolean)

    fun setShowTerminalOnMain(show: Boolean)

    fun setThemeMode(themeMode: AppThemeMode)

    fun setCustomThemeColors(colors: CustomThemeColors)

    fun setVpnMode(vpnMode: VpnMode)

    fun setSelectedAppPackages(packageNames: Set<String>)

    fun setActiveGlobalTab(tab: GlobalTab)

    fun setOpenSourceConsentVersion(version: Int)

    fun setShowOpenSourceWarningOnEnter(show: Boolean)

    fun setOpenSourceRiskBannerExpanded(expanded: Boolean)
}
