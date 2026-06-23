package com.stansful.sshvpnclient.ui.main

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.R
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.AppUpdateDownloadState
import com.stansful.sshvpnclient.domain.model.AppUpdateInfo
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.ui.common.AppScreen
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import com.stansful.sshvpnclient.ui.common.ErrorMessage
import com.stansful.sshvpnclient.ui.settings.SettingsSwitchRow
import com.stansful.sshvpnclient.ui.settings.ThemeModeSelector
import com.stansful.sshvpnclient.ui.settings.VpnModeSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun MainRoute(
    container: AppContainer,
    openConfigs: () -> Unit,
    openKeys: () -> Unit,
    openAppPicker: () -> Unit,
) {
    val viewModel: MainViewModel = viewModel(factory = AppViewModelFactory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var pendingUpdateInstallUri by remember { mutableStateOf<String?>(null) }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connect()
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }
    val installPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val canInstall = context.packageManager.canRequestPackageInstalls()
        val contentUri = pendingUpdateInstallUri
        if (contentUri != null && canInstall) {
            openUpdateInstaller(context, contentUri).onFailure { error ->
                viewModel.onUpdateActionFailed(error.message ?: "Unable to open Android installer")
            }
        } else if (contentUri != null) {
            viewModel.onUpdateActionFailed("Allow shadow-ssh to install unknown apps")
        }
        pendingUpdateInstallUri = null
    }

    val requestUpdateInstall = {
        val update = state.updateState.downloadState as? AppUpdateDownloadState.ReadyToInstall
        val contentUri = update?.contentUri
        if (contentUri == null) {
            viewModel.onUpdateActionFailed("Downloaded update is not ready to install")
        } else if (context.packageManager.canRequestPackageInstalls()) {
            openUpdateInstaller(context, contentUri).onFailure { error ->
                viewModel.onUpdateActionFailed(error.message ?: "Unable to open Android installer")
            }
        } else {
            pendingUpdateInstallUri = contentUri
            installPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri(),
                ),
            )
        }
        Unit
    }

    val requestUpdateDownload = {
        viewModel.downloadAvailableUpdate()
    }

    MainScreen(
        state = state,
        onConnect = {
            if (
                state.appSettings.vpnMode == VpnMode.SELECTED_APPS &&
                state.appSettings.selectedAppPackages.isEmpty()
            ) {
                viewModel.connect()
            } else {
                val permissionIntent = VpnService.prepare(context)
                if (permissionIntent != null) {
                    vpnPermissionLauncher.launch(permissionIntent)
                } else {
                    viewModel.connect()
                }
            }
        },
        onDisconnect = viewModel::disconnect,
        onCheckTunnel = viewModel::checkTunnel,
        openConfigs = openConfigs,
        openKeys = openKeys,
        openAppPicker = openAppPicker,
        onShowLogsChange = viewModel::setShowLogsOnMain,
        onShowTerminalChange = viewModel::setShowTerminalOnMain,
        onThemeModeChange = viewModel::setThemeMode,
        onCustomThemeColorsChange = viewModel::setCustomThemeColors,
        onVpnModeChange = viewModel::setVpnMode,
        onDismissNoSelectedApps = viewModel::dismissNoSelectedAppsDialog,
        onOpenTerminal = viewModel::openTerminal,
        onTerminalInputChange = viewModel::setTerminalInput,
        onSubmitTerminalInput = viewModel::submitTerminalInput,
        onCheckForUpdates = viewModel::checkForUpdates,
        onDismissUpdate = viewModel::dismissAvailableUpdate,
        onOpenUpdateRelease = { update -> uriHandler.openUri(update.releaseUrl) },
        onDownloadUpdate = requestUpdateDownload,
        onInstallUpdate = requestUpdateInstall,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    state: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCheckTunnel: () -> Unit,
    openConfigs: () -> Unit,
    openKeys: () -> Unit,
    openAppPicker: () -> Unit,
    onShowLogsChange: (Boolean) -> Unit,
    onShowTerminalChange: (Boolean) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onCustomThemeColorsChange: (CustomThemeColors) -> Unit,
    onVpnModeChange: (VpnMode) -> Unit,
    onDismissNoSelectedApps: () -> Unit,
    onOpenTerminal: () -> Unit,
    onTerminalInputChange: (String) -> Unit,
    onSubmitTerminalInput: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDismissUpdate: () -> Unit,
    onOpenUpdateRelease: (AppUpdateInfo) -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    var settingsVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.updateState.availableUpdate) {
        if (state.updateState.availableUpdate != null) {
            settingsVisible = false
        }
    }

    AppScreen(
        title = "Shadow SSH VPN",
        actions = {
            IconButton(onClick = { settingsVisible = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionPanel(
                state = state,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onCheckTunnel = onCheckTunnel,
            )
            SelectedConfigPanel(state)
            NavigationPanel(
                openConfigs = openConfigs,
                openKeys = openKeys,
                showAddConfiguration = state.selectedConfig == null,
            )

            AnimatedVisibility(
                visible = state.appSettings.showLogsOnMain && state.showSshDiagnostics,
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 4 },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { it / 4 },
            ) {
                DiagnosticsPanel(state)
            }

            AnimatedVisibility(
                visible = state.appSettings.showTerminalOnMain &&
                    (state.isConnected ||
                        state.terminalState.isOpen ||
                        state.terminalState.isConnecting ||
                        state.terminalState.output.isNotBlank()),
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 4 },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { it / 4 },
            ) {
                TerminalPanel(
                    state = state,
                    onOpenTerminal = onOpenTerminal,
                    onTerminalInputChange = onTerminalInputChange,
                    onSubmitTerminalInput = onSubmitTerminalInput,
                )
            }
        }
    }

    if (settingsVisible) {
        SettingsSheet(
            settings = state.appSettings,
            onShowLogsChange = onShowLogsChange,
            onShowTerminalChange = onShowTerminalChange,
            onThemeModeChange = onThemeModeChange,
            onCustomThemeColorsChange = onCustomThemeColorsChange,
            onVpnModeChange = onVpnModeChange,
            onOpenAppPicker = {
                settingsVisible = false
                openAppPicker()
            },
            updateState = state.updateState,
            onCheckForUpdates = onCheckForUpdates,
            onInstallUpdate = onInstallUpdate,
            onDismiss = { settingsVisible = false },
        )
    }

    if (state.showNoSelectedAppsDialog) {
        AlertDialog(
            onDismissRequest = onDismissNoSelectedApps,
            confirmButton = {
                TextButton(onClick = onDismissNoSelectedApps) {
                    Text("OK")
                }
            },
            title = { Text(stringResource(R.string.vpn_mode)) },
            text = { Text(stringResource(R.string.error_no_selected_apps)) },
        )
    }

    state.updateState.availableUpdate?.let { update ->
        UpdateAvailableDialog(
            update = update,
            onLater = onDismissUpdate,
            onOpenRelease = { onOpenUpdateRelease(update) },
            downloadState = state.updateState.downloadState,
            onDownload = {
                onDownloadUpdate()
                settingsVisible = true
            },
            onInstall = onInstallUpdate,
        )
    }
}

@Composable
private fun ConnectionPanel(
    state: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCheckTunnel: () -> Unit,
) {
    val statusText = state.sshStatus.label()
    val statusColor by animateColorAsState(
        targetValue = state.sshStatus.statusColor(),
        animationSpec = tween(240),
        label = "status-color",
    )

    GlassPanel {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "Secure Tunnel",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        state.selectedConfig?.let { "${it.username}@${it.host}:${it.port}" }
                            ?: "No configuration selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(text = statusText, color = statusColor)
            }

            ErrorMessage(state.sshErrorMessage)

            ConnectionActionButton(
                state = state,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
            )

            AnimatedVisibility(
                visible = state.isConnected,
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 4 },
                exit = fadeOut(tween(120)),
            ) {
                TunnelCheckButton(
                    state = state,
                    onCheckTunnel = onCheckTunnel,
                )
            }
        }
    }
}

@Composable
private fun TunnelCheckButton(
    state: MainUiState,
    onCheckTunnel: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(120),
        label = "tunnel-check-button-scale",
    )
    val containerColor by animateColorAsState(
        targetValue = state.tunnelCheckResult.buttonContainerColor(),
        animationSpec = tween(180),
        label = "tunnel-check-button-container",
    )
    val contentColor by animateColorAsState(
        targetValue = state.tunnelCheckResult.buttonContentColor(),
        animationSpec = tween(180),
        label = "tunnel-check-button-content",
    )
    val resultIcon = state.tunnelCheckResult.buttonIcon()
    val textStartPadding = if (resultIcon == null) 0.dp else 8.dp

    FilledTonalButton(
        onClick = onCheckTunnel,
        enabled = state.canCheckTunnel,
        interactionSource = interactionSource,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.62f),
            disabledContentColor = contentColor.copy(alpha = 0.72f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(8.dp),
    ) {
        resultIcon?.let { icon ->
            Icon(icon, contentDescription = null)
        }
        Text(
            text = if (state.isTunnelCheckRunning) {
                "Checking youtube.com..."
            } else {
                "Check tunnel"
            },
            modifier = Modifier.padding(start = textStartPadding),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TunnelCheckResult.buttonContainerColor(): Color {
    return when (this) {
        TunnelCheckResult.IDLE -> MaterialTheme.colorScheme.surfaceVariant
        TunnelCheckResult.SUCCESS -> MaterialTheme.colorScheme.secondary
        TunnelCheckResult.FAILURE -> MaterialTheme.colorScheme.error
    }
}

@Composable
private fun TunnelCheckResult.buttonContentColor(): Color {
    return when (this) {
        TunnelCheckResult.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        TunnelCheckResult.SUCCESS -> MaterialTheme.colorScheme.onSecondary
        TunnelCheckResult.FAILURE -> MaterialTheme.colorScheme.onError
    }
}

private fun TunnelCheckResult.buttonIcon(): ImageVector? {
    return when (this) {
        TunnelCheckResult.IDLE -> null
        TunnelCheckResult.SUCCESS -> Icons.Default.Check
        TunnelCheckResult.FAILURE -> Icons.Default.Close
    }
}

@Composable
private fun ConnectionActionButton(
    state: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val buttonText = when {
        state.canDisconnect -> "Disconnect"
        state.isOpenSourceActive -> "Switch to SSH"
        else -> "Connect"
    }
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(120),
        label = "connection-button-scale",
    )

    Button(
        onClick = if (state.canDisconnect) onDisconnect else onConnect,
        enabled = if (state.canDisconnect) {
            state.sshStatus != VpnConnectionStatus.DISCONNECTING
        } else {
            state.canConnect
        },
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (state.canDisconnect) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            contentColor = if (state.canDisconnect) {
                MaterialTheme.colorScheme.onError
            } else {
                MaterialTheme.colorScheme.onPrimary
            },
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
        Text(
            text = buttonText,
            modifier = Modifier.padding(start = 8.dp),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatusBadge(
    text: String,
    color: Color,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f)),
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun SelectedConfigPanel(state: MainUiState) {
    val config = state.selectedConfig
    GlassPanel {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Selected Config", style = MaterialTheme.typography.labelLarge)
            if (config == null) {
                Text("No configuration selected", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }

            Text(config.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${config.username}@${config.host}:${config.port}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Auth: ${config.authType.label}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (config.authType == AuthType.PRIVATE_KEY) {
                Text("Key: ${state.selectedKeyName ?: "Missing key"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("KeepAlive: ${config.keepAliveIntervalSec} sec", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (config.enableUdpForwarding) {
                Text("UDP forwarding: experimental", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            config.note?.let { Text("Note: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun NavigationPanel(
    openConfigs: () -> Unit,
    openKeys: () -> Unit,
    showAddConfiguration: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassNavButton(
                text = "Configurations",
                icon = Icons.Default.Settings,
                onClick = openConfigs,
                modifier = Modifier.weight(1f),
            )
            GlassNavButton(
                text = "SSH Keys",
                icon = Icons.Default.Key,
                onClick = openKeys,
                modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(
            visible = showAddConfiguration,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
            exit = fadeOut(tween(120)),
        ) {
            FilledTonalButton(
                onClick = openConfigs,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Add first configuration")
            }
        }
    }
}

@Composable
private fun GlassNavButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(120),
        label = "nav-button-scale",
    )

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = glassAlpha()),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null)
            Text(
                text,
                modifier = Modifier.padding(start = 8.dp),
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun DiagnosticsPanel(state: MainUiState) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val diagnostics = state.vpnState.diagnostics

    GlassPanel {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Connection Diagnostics", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${state.vpnState.diagnostics.size} lines",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                val logText = withContext(Dispatchers.Default) {
                                    diagnostics.joinToString(separator = "\n")
                                }
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("Connection diagnostics", logText)),
                                )
                            }
                        },
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy diagnostics")
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        val icon = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore
                        val description = if (expanded) "Hide diagnostics" else "Show diagnostics"
                        Icon(icon, contentDescription = description)
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { -it / 5 },
                exit = fadeOut(tween(120)),
            ) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(
                        items = diagnostics,
                        key = { index, _ -> index },
                    ) { _, line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    settings: AppSettings,
    onShowLogsChange: (Boolean) -> Unit,
    onShowTerminalChange: (Boolean) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onCustomThemeColorsChange: (CustomThemeColors) -> Unit,
    onVpnModeChange: (VpnMode) -> Unit,
    onOpenAppPicker: () -> Unit,
    updateState: AppUpdateUiState,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.42f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline) },
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            SettingsSwitchRow(
                title = "Debug logs",
                checked = settings.showLogsOnMain,
                onCheckedChange = onShowLogsChange,
            )
            SettingsSwitchRow(
                title = "SSH terminal",
                checked = settings.showTerminalOnMain,
                onCheckedChange = onShowTerminalChange,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("VPN mode", style = MaterialTheme.typography.labelLarge)
                VpnModeSelector(
                    selected = settings.vpnMode,
                    selectedAppsCount = settings.selectedAppPackages.size,
                    onSelected = onVpnModeChange,
                    onOpenAppPicker = onOpenAppPicker,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                ThemeModeSelector(
                    selected = settings.themeMode,
                    onSelected = onThemeModeChange,
                )
                AnimatedVisibility(visible = settings.themeMode == AppThemeMode.CUSTOM) {
                    CustomThemeColorsEditor(
                        colors = settings.customThemeColors,
                        onColorsChange = onCustomThemeColorsChange,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Updates", style = MaterialTheme.typography.labelLarge)
                FilledTonalButton(
                    onClick = onCheckForUpdates,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    if (updateState.isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                    Text(
                        text = if (updateState.isChecking) "Checking" else "Check for updates",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                UpdateDownloadStatus(
                    downloadState = updateState.downloadState,
                    onInstall = onInstallUpdate,
                )
                updateState.statusMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            GitHubLinkRow(
                onClick = { uriHandler.openUri(GITHUB_REPOSITORY_URL) },
                onCopyClick = {
                    coroutineScope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("GitHub repository", GITHUB_REPOSITORY_URL)),
                        )
                    }
                },
            )

            Box(modifier = Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable
private fun UpdateAvailableDialog(
    update: AppUpdateInfo,
    downloadState: AppUpdateDownloadState,
    onLater: () -> Unit,
    onOpenRelease: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    val readyToInstall = (downloadState as? AppUpdateDownloadState.ReadyToInstall)
        ?.takeIf { it.versionName == update.versionName }
    val downloading = (downloadState as? AppUpdateDownloadState.Downloading)
        ?.takeIf { it.versionName == update.versionName }
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Update available: ${update.versionName}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(update.title, fontWeight = FontWeight.SemiBold)
                Text(
                    text = update.releaseNotes.ifBlank { "Release notes are available on GitHub." },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (update.apkSizeBytes > 0L) {
                    Text(
                        text = "APK size: ${formatFileSize(update.apkSizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                downloading?.let { state ->
                    DownloadProgressContent(state)
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
            ) {
                FilledTonalButton(
                    onClick = if (readyToInstall != null) onInstall else onDownload,
                    enabled = downloading == null,
                ) {
                    Icon(
                        imageVector = if (readyToInstall != null) {
                            Icons.Default.SystemUpdateAlt
                        } else {
                            Icons.Default.Download
                        },
                        contentDescription = null,
                    )
                    Text(
                        text = when {
                            readyToInstall != null -> "Install"
                            downloading != null -> "Downloading"
                            else -> "Download"
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Row {
                    TextButton(onClick = onOpenRelease) { Text("Open release") }
                    TextButton(onClick = onLater) { Text("Later") }
                }
            }
        },
    )
}

@Composable
private fun UpdateDownloadStatus(
    downloadState: AppUpdateDownloadState,
    onInstall: () -> Unit,
) {
    when (downloadState) {
        is AppUpdateDownloadState.Downloading -> {
            var expanded by remember(downloadState.versionName) { mutableStateOf(true) }
            Surface(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Text(
                            text = "Downloading ${downloadState.versionName}",
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        downloadState.progressPercent?.let { percent ->
                            Text("$percent%", style = MaterialTheme.typography.labelLarge)
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Collapse download progress" else "Expand download progress",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    AnimatedVisibility(visible = expanded) {
                        DownloadProgressContent(downloadState)
                    }
                }
            }
        }

        is AppUpdateDownloadState.ReadyToInstall -> {
            FilledTonalButton(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Default.SystemUpdateAlt, contentDescription = null)
                Text(
                    text = "Install shadow-ssh ${downloadState.versionName}",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        is AppUpdateDownloadState.Failed,
        AppUpdateDownloadState.Idle,
        -> Unit
    }
}

@Composable
private fun DownloadProgressContent(downloadState: AppUpdateDownloadState.Downloading) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val progress = downloadState.progressFraction
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        val totalBytes = downloadState.totalBytes
        Text(
            text = when {
                downloadState.isPaused -> "Download paused by Android"
                totalBytes != null -> {
                    "${formatFileSize(downloadState.downloadedBytes)} of ${formatFileSize(totalBytes)}"
                }
                else -> "Waiting for download size"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GitHubLinkRow(
    onClick: () -> Unit,
    onCopyClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(120),
        label = "github-link-scale",
    )
    val colorScheme = MaterialTheme.colorScheme
    val darkTheme = colorScheme.background.luminance() < 0.5f

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (darkTheme) {
            colorScheme.surfaceVariant.copy(alpha = 0.72f)
        } else {
            colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        contentColor = colorScheme.onSurface,
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.28f)),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_github_mark),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = colorScheme.onSurface,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "GitHub",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "stansful/ssh-vpn-client-kotlin",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onCopyClick,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy GitHub link",
                    tint = colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val colorScheme = MaterialTheme.colorScheme
    val darkTheme = colorScheme.background.luminance() < 0.5f
    val gradientStart = if (darkTheme) {
        colorScheme.surface.copy(alpha = 0.98f)
    } else {
        colorScheme.surface.copy(alpha = 0.82f)
    }
    val gradientEnd = if (darkTheme) {
        colorScheme.surfaceVariant.copy(alpha = 0.72f)
    } else {
        colorScheme.surfaceVariant.copy(alpha = 0.28f)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.28f)), shape),
        shape = shape,
        color = colorScheme.surface.copy(alpha = glassAlpha()),
        contentColor = colorScheme.onSurface,
        shadowElevation = if (darkTheme) 0.dp else 2.dp,
    ) {
        Box(
            modifier = Modifier.background(
                Brush.verticalGradient(
                    colors = listOf(
                        gradientStart,
                        gradientEnd,
                    ),
                ),
            ),
        ) {
            content()
        }
    }
}

@Composable
private fun glassAlpha(): Float {
    return if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.94f else 0.78f
}

private fun VpnConnectionStatus.label(): String {
    return when (this) {
        VpnConnectionStatus.DISCONNECTED -> "Disconnected"
        VpnConnectionStatus.CONNECTING -> "Connecting"
        VpnConnectionStatus.CONNECTED -> "Connected"
        VpnConnectionStatus.RECONNECTING -> "Reconnecting"
        VpnConnectionStatus.DISCONNECTING -> "Disconnecting"
        VpnConnectionStatus.ERROR -> "Error"
    }
}

@Composable
private fun VpnConnectionStatus.statusColor(): Color {
    return when (this) {
        VpnConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.secondary
        VpnConnectionStatus.CONNECTING,
        VpnConnectionStatus.RECONNECTING
        -> MaterialTheme.colorScheme.primary
        VpnConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
        VpnConnectionStatus.DISCONNECTING -> MaterialTheme.colorScheme.tertiary
        VpnConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun formatFileSize(sizeBytes: Long): String {
    val mebibytes = sizeBytes.toDouble() / (1_024.0 * 1_024.0)
    return String.format(Locale.US, "%.1f MiB", mebibytes)
}

private fun openUpdateInstaller(context: Context, contentUri: String): Result<Unit> = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri.toUri(), APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}

private const val GITHUB_REPOSITORY_URL =
    "https://github.com/stansful/ssh-vpn-client-kotlin/tree/master"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
