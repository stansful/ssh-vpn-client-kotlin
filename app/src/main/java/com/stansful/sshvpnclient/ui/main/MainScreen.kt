package com.stansful.sshvpnclient.ui.main

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.ui.common.AppScreen
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import com.stansful.sshvpnclient.ui.common.ErrorMessage
import com.stansful.sshvpnclient.ui.common.PrimaryActionButton
import com.stansful.sshvpnclient.ui.common.SecondaryActionButton
import com.stansful.sshvpnclient.ui.common.VerticalGap
import androidx.compose.runtime.collectAsState

@Composable
fun MainRoute(
    container: AppContainer,
    openConfigs: () -> Unit,
    openKeys: () -> Unit,
) {
    val viewModel: MainViewModel = viewModel(factory = AppViewModelFactory(container))
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connect()
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }

    MainScreen(
        state = state,
        onConnect = {
            val permissionIntent = VpnService.prepare(context)
            if (permissionIntent != null) {
                vpnPermissionLauncher.launch(permissionIntent)
            } else {
                viewModel.connect()
            }
        },
        onDisconnect = viewModel::disconnect,
        openConfigs = openConfigs,
        openKeys = openKeys,
    )
}

@Composable
private fun MainScreen(
    state: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    openConfigs: () -> Unit,
    openKeys: () -> Unit,
) {
    AppScreen(title = "SSH VPN") {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusPanel(state)
            SelectedConfigPanel(state)
            DiagnosticsPanel(state)

            if (state.isConnected) {
                PrimaryActionButton(
                    text = "Disconnect",
                    onClick = onDisconnect,
                    enabled = !state.isBusy,
                )
            } else {
                PrimaryActionButton(
                    text = "Connect",
                    onClick = onConnect,
                    enabled = state.selectedConfig != null && !state.isBusy,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = openConfigs,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Text("Configurations", modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = openKeys,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Key, contentDescription = null)
                    Text("SSH Keys", modifier = Modifier.padding(start = 8.dp))
                }
            }

            if (state.selectedConfig == null) {
                SecondaryActionButton(text = "Add first configuration", onClick = openConfigs)
            }
        }
    }
}

@Composable
private fun StatusPanel(state: MainUiState) {
    val statusText = when (state.vpnState.status) {
        VpnConnectionStatus.DISCONNECTED -> "Disconnected"
        VpnConnectionStatus.CONNECTING -> "Connecting"
        VpnConnectionStatus.CONNECTED -> "Connected"
        VpnConnectionStatus.DISCONNECTING -> "Disconnecting"
        VpnConnectionStatus.ERROR -> "Error"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("VPN Status", style = MaterialTheme.typography.labelLarge)
            AssistChip(
                onClick = {},
                label = { Text(statusText) },
            )
            ErrorMessage(state.vpnState.errorMessage)
        }
    }
}

@Composable
private fun DiagnosticsPanel(state: MainUiState) {
    if (state.vpnState.diagnostics.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Connection diagnostics", style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(
                    text = state.vpnState.diagnostics.joinToString(separator = "\n"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SelectedConfigPanel(state: MainUiState) {
    val config = state.selectedConfig
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Selected config", style = MaterialTheme.typography.labelLarge)
            if (config == null) {
                Text("No configuration selected", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }

            Text(config.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${config.username}@${config.host}:${config.port}")
            Text("Auth: ${config.authType.label}")
            if (config.authType == AuthType.PRIVATE_KEY) {
                Text("Key: ${state.selectedKeyName ?: "Missing key"}")
            }
            Text("KeepAlive: ${config.keepAliveIntervalSec} sec")
            if (config.enableUdpForwarding) {
                Text("UDP forwarding: experimental")
            }
            config.note?.let { Text("Note: $it") }
        }
    }
}
