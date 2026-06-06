package com.stansful.sshvpnclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import com.stansful.sshvpnclient.ui.SshVpnNavGraph
import com.stansful.sshvpnclient.ui.theme.SshVpnTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as SshVpnApplication).container

        setContent {
            val settings by container.appSettingsRepository.settings.collectAsState()
            SshVpnTheme(themeMode = settings.themeMode) {
                SshVpnNavGraph(
                    container = container,
                    navController = rememberNavController(),
                )
            }
        }
    }
}
