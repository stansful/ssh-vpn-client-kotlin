package com.stansful.sshvpnclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.ui.SshVpnNavGraph
import com.stansful.sshvpnclient.ui.theme.SshVpnTheme
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as SshVpnApplication).container

        setContent {
            val settings by container.appSettingsRepository.settings.collectAsStateWithLifecycle()
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                AppThemeMode.SYSTEM -> systemDarkTheme
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
                AppThemeMode.CUSTOM -> Color(settings.customThemeColors.background).luminance() < DARK_LUMINANCE_THRESHOLD
            }
            val systemBarColor = when (settings.themeMode) {
                AppThemeMode.CUSTOM -> settings.customThemeColors.background
                else -> if (darkTheme) AndroidColor.BLACK else AndroidColor.WHITE
            }

            SideEffect {
                updateSystemBars(
                    darkTheme = darkTheme,
                    systemBarColor = systemBarColor,
                )
            }

            SshVpnTheme(
                themeMode = settings.themeMode,
                customThemeColors = settings.customThemeColors,
            ) {
                SshVpnNavGraph(
                    container = container,
                    navController = rememberNavController(),
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun updateSystemBars(
        darkTheme: Boolean,
        systemBarColor: Int,
    ) {
        window.statusBarColor = systemBarColor
        window.navigationBarColor = systemBarColor
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    private companion object {
        const val DARK_LUMINANCE_THRESHOLD = 0.5f
    }
}
