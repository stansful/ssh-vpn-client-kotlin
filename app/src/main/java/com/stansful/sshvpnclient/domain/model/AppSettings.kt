package com.stansful.sshvpnclient.domain.model

data class AppSettings(
    val showLogsOnMain: Boolean = false,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
)

enum class AppThemeMode(
    val storageValue: String,
    val label: String,
) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromStorageValue(value: String?): AppThemeMode {
            return values().firstOrNull { it.storageValue == value } ?: SYSTEM
        }
    }
}
