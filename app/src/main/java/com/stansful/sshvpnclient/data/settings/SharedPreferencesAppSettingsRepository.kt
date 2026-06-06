package com.stansful.sshvpnclient.data.settings

import android.content.Context
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
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
        preferences.edit()
            .putBoolean(KEY_SHOW_LOGS_ON_MAIN, show)
            .apply()
        currentSettings.value = currentSettings.value.copy(showLogsOnMain = show)
    }

    override fun setThemeMode(themeMode: AppThemeMode) {
        preferences.edit()
            .putString(KEY_THEME_MODE, themeMode.storageValue)
            .apply()
        currentSettings.value = currentSettings.value.copy(themeMode = themeMode)
    }

    private fun readSettings(): AppSettings {
        return AppSettings(
            showLogsOnMain = preferences.getBoolean(KEY_SHOW_LOGS_ON_MAIN, false),
            themeMode = AppThemeMode.fromStorageValue(preferences.getString(KEY_THEME_MODE, null)),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "ssh-vpn-client-settings"
        const val KEY_SHOW_LOGS_ON_MAIN = "show_logs_on_main"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
