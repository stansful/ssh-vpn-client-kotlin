package com.stansful.sshvpnclient.ui.main

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.ui.common.AppUpdateAvailableDialog
import com.stansful.sshvpnclient.ui.common.AppUpdateSettingsSection
import com.stansful.sshvpnclient.ui.common.AppUpdateUiState
import com.stansful.sshvpnclient.ui.common.AppScreen
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import com.stansful.sshvpnclient.ui.common.ErrorMessage
import com.stansful.sshvpnclient.ui.common.AppSheetCornerRadius
import com.stansful.sshvpnclient.ui.common.InsetGroup
import com.stansful.sshvpnclient.ui.common.InsetRow
import com.stansful.sshvpnclient.ui.common.SectionHeader
import com.stansful.sshvpnclient.ui.common.SheetTitle
import com.stansful.sshvpnclient.ui.common.StatusCapsule
import com.stansful.sshvpnclient.ui.common.openAppUpdateInstaller
import com.stansful.sshvpnclient.ui.settings.SettingsSwitchRow
import com.stansful.sshvpnclient.ui.settings.ThemeModeSelector
import com.stansful.sshvpnclient.ui.settings.VpnModeSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainRoute(
    container: AppContainer,
    openConfigs: () -> Unit,
    openKeys: () -> Unit,
    openAppPicker: () -> Unit,
) {
    val viewModel: MainViewModel = viewModel(factory = AppViewModelFactory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var pendingUpdateInstallUri by remember { mutableStateOf<String?>(null) }

    DisposableEffect(viewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.closeTerminal()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.closeTerminal()
        }
    }

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
            openAppUpdateInstaller(context, contentUri).onFailure { error ->
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
            openAppUpdateInstaller(context, contentUri).onFailure { error ->
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
        onCloseTerminal = viewModel::closeTerminal,
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
    onCloseTerminal: () -> Unit,
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
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ConnectionPanel(
                state = state,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onCheckTunnel = onCheckTunnel,
            )
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
                    onCloseTerminal = onCloseTerminal,
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
            onResumeUpdate = onDownloadUpdate,
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
        AppUpdateAvailableDialog(
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Connection")
        InsetGroup {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = state.selectedConfig?.name ?: "No configuration",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            state.selectedConfig?.let { "${it.username}@${it.host}:${it.port}" }
                                ?: "Choose an SSH configuration to connect.",
                            style = if (state.selectedConfig != null) {
                                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
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
                    enter = fadeIn(tween(160)),
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
            .heightIn(min = 52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = MaterialTheme.shapes.medium,
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
        targetValue = if (pressed) 0.985f else 1f,
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
            .heightIn(min = 54.dp)
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
        shape = MaterialTheme.shapes.medium,
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
    StatusCapsule(text = text, color = color)
}

@Composable
private fun NavigationPanel(
    openConfigs: () -> Unit,
    openKeys: () -> Unit,
    showAddConfiguration: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Manage")
        InsetGroup(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Column {
                InsetRow(
                    title = "Configurations",
                    subtitle = "Servers and connection policies",
                    icon = Icons.Default.Settings,
                    onClick = openConfigs,
                    trailing = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                )
                InsetRow(
                    title = "SSH Keys",
                    subtitle = "Private identities stored on device",
                    icon = Icons.Default.Key,
                    onClick = openKeys,
                    trailing = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                )
            }
        }
        AnimatedVisibility(
            visible = showAddConfiguration,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(120)),
        ) {
            FilledTonalButton(
                onClick = openConfigs,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Add first configuration")
            }
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
            modifier = Modifier.padding(16.dp),
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
                        pluralStringResource(
                            R.plurals.diagnostics_line_count,
                            diagnostics.size,
                            diagnostics.size,
                        ),
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
    onResumeUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.42f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline) },
        shape = RoundedCornerShape(
            topStart = AppSheetCornerRadius,
            topEnd = AppSheetCornerRadius,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SheetTitle(
                title = "Settings",
                subtitle = "Connection, appearance and app routing",
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("General")
                InsetGroup(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Column {
                        SettingsSwitchRow(
                            title = "Debug logs",
                            checked = settings.showLogsOnMain,
                            onCheckedChange = onShowLogsChange,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                        )
                        SettingsSwitchRow(
                            title = "SSH terminal",
                            checked = settings.showTerminalOnMain,
                            onCheckedChange = onShowTerminalChange,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("VPN mode")
                InsetGroup {
                    VpnModeSelector(
                        selected = settings.vpnMode,
                        selectedAppsCount = settings.selectedAppPackages.size,
                        onSelected = onVpnModeChange,
                        onOpenAppPicker = onOpenAppPicker,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Theme")
                InsetGroup {
                    ThemeModeSelector(
                        selected = settings.themeMode,
                        onSelected = onThemeModeChange,
                    )
                }
                AnimatedVisibility(visible = settings.themeMode == AppThemeMode.CUSTOM) {
                    CustomThemeColorsEditor(
                        colors = settings.customThemeColors,
                        onColorsChange = onCustomThemeColorsChange,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Updates")
                InsetGroup {
                    AppUpdateSettingsSection(
                        updateState = updateState,
                        onCheckForUpdates = onCheckForUpdates,
                        onResumeUpdate = onResumeUpdate,
                        onInstallUpdate = onInstallUpdate,
                        showTitle = false,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("About")
                GitHubLinkRow(
                    onClick = { uriHandler.openUri(GITHUB_REPOSITORY_URL) },
                    onCopyClick = {
                        coroutineScope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(
                                    ClipData.newPlainText("GitHub repository", GITHUB_REPOSITORY_URL),
                                ),
                            )
                        }
                    },
                )
            }

            Box(modifier = Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable
private fun GitHubLinkRow(
    onClick: () -> Unit,
    onCopyClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    InsetGroup(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 11.dp),
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
                modifier = Modifier.size(48.dp),
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
    InsetGroup(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        content = content,
    )
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

private const val GITHUB_REPOSITORY_URL =
    "https://github.com/stansful/ssh-vpn-client-kotlin/tree/master"
