package com.stansful.sshvpnclient

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.ui.SshVpnNavGraph
import com.stansful.sshvpnclient.ui.theme.SshVpnTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as SshVpnApplication).container

        setContent {
            val settings by container.appSettingsRepository.settings.collectAsState()
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                AppThemeMode.SYSTEM -> systemDarkTheme
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }

            SideEffect {
                updateSystemBars(darkTheme)
            }

            SshVpnTheme(themeMode = settings.themeMode) {
                SshVpnNavGraph(
                    container = container,
                    navController = rememberNavController(),
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun updateSystemBars(darkTheme: Boolean) {
        val systemBarColor = if (darkTheme) Color.BLACK else Color.WHITE
        window.statusBarColor = systemBarColor
        window.navigationBarColor = systemBarColor
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
