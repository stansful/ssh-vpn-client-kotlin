package com.stansful.sshvpnclient.domain.model

data class AppSettings(
    val showLogsOnMain: Boolean = false,
    val showLogsOnOpenSource: Boolean = false,
    val showTerminalOnMain: Boolean = false,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val customThemeColors: CustomThemeColors = CustomThemeColors.defaultLight(),
    val vpnMode: VpnMode = VpnMode.PROXY,
    val selectedAppPackages: Set<String> = emptySet(),
    val activeGlobalTab: GlobalTab = GlobalTab.SHADOW_SSH,
    val openSourceConsentVersion: Int = 0,
    val showOpenSourceWarningOnEnter: Boolean = true,
    val openSourceRiskBannerExpanded: Boolean = true,
    val openSourceAutoUpdateEnabled: Boolean = true,
)

enum class GlobalTab(
    val storageValue: String,
    val label: String,
) {
    SHADOW_SSH("shadow-ssh", "shadow-ssh"),
    OPEN_SOURCE("opensource", "opensource");

    companion object {
        fun fromStorageValue(value: String?): GlobalTab {
            return entries.firstOrNull { it.storageValue == value } ?: SHADOW_SSH
        }
    }
}

data class CustomThemeColors(
    val primary: Int,
    val secondary: Int,
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val onSurface: Int,
    val outline: Int,
    val error: Int,
) {
    companion object {
        fun defaultLight(): CustomThemeColors {
            return CustomThemeColors(
                primary = 0xFF007AFF.toInt(),
                secondary = 0xFF26A69A.toInt(),
                background = 0xFFFFFFFF.toInt(),
                surface = 0xFFFFFFFF.toInt(),
                surfaceVariant = 0xFFE8EDF3.toInt(),
                onSurface = 0xFF101214.toInt(),
                outline = 0xFFCBD3DC.toInt(),
                error = 0xFFBA1A1A.toInt(),
            )
        }
    }
}

enum class AppThemeMode(
    val storageValue: String,
    val label: String,
) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    CUSTOM("custom", "Custom");

    companion object {
        fun fromStorageValue(value: String?): AppThemeMode {
            return entries.firstOrNull { it.storageValue == value } ?: SYSTEM
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
            return entries.firstOrNull { it.storageValue == value } ?: PROXY
        }
    }
}
