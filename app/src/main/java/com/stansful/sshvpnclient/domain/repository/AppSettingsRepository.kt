package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import kotlinx.coroutines.flow.StateFlow

interface AppSettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setShowLogsOnMain(show: Boolean)

    fun setThemeMode(themeMode: AppThemeMode)
}
