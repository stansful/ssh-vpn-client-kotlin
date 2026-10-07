package com.stansful.sshvpnclient

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stansful.sshvpnclient.ui.shell.Destinations
import com.stansful.sshvpnclient.ui.shell.ShadowApp
import com.stansful.sshvpnclient.ui.shell.ShellLaunchRequest
import com.stansful.sshvpnclient.ui.theme.ShadowTheme
import com.stansful.sshvpnclient.ui.theme.shadowColorsFor
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val launchRequest = MutableStateFlow<ShellLaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val container = (application as SshVpnApplication).container
        val initialSettings = container.appSettingsRepository.settings.value
        val systemDarkAtLaunch = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        applySystemBarStyle(
            isDark = shadowColorsFor(
                themeMode = initialSettings.themeMode,
                customThemeColors = initialSettings.customThemeColors,
                systemDark = systemDarkAtLaunch,
            ).isDark,
        )
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) launchRequest.value = intent.toLaunchRequest()

        setContent {
            val settings by container.appSettingsRepository.settings.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val isDark = remember(settings.themeMode, settings.customThemeColors, systemDark) {
                shadowColorsFor(settings.themeMode, settings.customThemeColors, systemDark).isDark
            }
            LaunchedEffect(isDark) { applySystemBarStyle(isDark) }
            val request by launchRequest.collectAsStateWithLifecycle()

            ShadowTheme(
                themeMode = settings.themeMode,
                customThemeColors = settings.customThemeColors,
            ) {
                ShadowApp(
                    container = container,
                    launchRequest = request,
                    onLaunchRequestHandled = { launchRequest.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.toLaunchRequest()?.let { launchRequest.value = it }
    }

    /**
     * Transparent status and navigation bars with icons for the app palette (not the system theme).
     * An explicit style also turns off the system's contrast scrim behind 3-button navigation, so the
     * bottom nav's own background shows through it.
     */
    private fun applySystemBarStyle(isDark: Boolean) {
        val style = if (isDark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private fun Intent?.toLaunchRequest(): ShellLaunchRequest? {
        // The activity is exported: accept only the top-level routes (the quick tile sends Home).
        val route = Destinations.externalRouteOrNull(this?.getStringExtra(EXTRA_DESTINATION)) ?: return null
        return ShellLaunchRequest(id = SystemClock.elapsedRealtimeNanos(), route = route)
    }

    companion object {
        /**
         * Optional top-level route (see `ui/shell/Destinations.externalRouteOrNull`) to show when the
         * activity is opened or reopened; other values are ignored.
         */
        const val EXTRA_DESTINATION = "com.stansful.sshvpnclient.extra.DESTINATION"
    }
}
