package com.stansful.sshvpnclient.domain.model

data class AppSettings(
    val showLogsOnMain: Boolean = false,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val vpnMode: VpnMode = VpnMode.PROXY,
    val selectedAppPackages: Set<String> = emptySet(),
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

enum class VpnMode(
    val storageValue: String,
    val label: String,
) {
    PROXY("proxy", "Proxy"),
    SELECTED_APPS("selected-apps", "Selected apps");

    companion object {
        fun fromStorageValue(value: String?): VpnMode {
            return values().firstOrNull { it.storageValue == value } ?: PROXY
        }
    }
}
