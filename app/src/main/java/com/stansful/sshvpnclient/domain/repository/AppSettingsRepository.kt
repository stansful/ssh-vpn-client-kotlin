package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.VpnMode
import kotlinx.coroutines.flow.StateFlow

interface AppSettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setShowLogsOnMain(show: Boolean)

    fun setThemeMode(themeMode: AppThemeMode)

    fun setVpnMode(vpnMode: VpnMode)

    fun setSelectedAppPackages(packageNames: Set<String>)
}
