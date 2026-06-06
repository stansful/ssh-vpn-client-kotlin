package com.stansful.sshvpnclient.ui.main

import android.app.Activity
import android.net.VpnService
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.ui.common.AppScreen
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import com.stansful.sshvpnclient.ui.common.ErrorMessage

@Composable
fun MainRoute(
    container: AppContainer,
    openConfigs: () -> Unit,
    openKeys: () -> Unit,
    openAppPicker: () -> Unit,
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
        openConfigs = openConfigs,
        openKeys = openKeys,
        openAppPicker = openAppPicker,
        onShowLogsChange = viewModel::setShowLogsOnMain,
        onThemeModeChange = viewModel::setThemeMode,
        onVpnModeChange = viewModel::setVpnMode,
        onDismissNoSelectedApps = viewModel::dismissNoSelectedAppsDialog,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    state: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    openConfigs: () -> Unit,
    openKeys: () -> Unit,
    openAppPicker: () -> Unit,
    onShowLogsChange: (Boolean) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onVpnModeChange: (VpnMode) -> Unit,
    onDismissNoSelectedApps: () -> Unit,
) {
    var settingsVisible by remember { mutableStateOf(false) }

    AppScreen(
        title = "SSH VPN",
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
            )
            SelectedConfigPanel(state)
            NavigationPanel(
                openConfigs = openConfigs,
                openKeys = openKeys,
                showAddConfiguration = state.selectedConfig == null,
            )

            AnimatedVisibility(
                visible = state.appSettings.showLogsOnMain && state.vpnState.diagnostics.isNotEmpty(),
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 4 },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { it / 4 },
            ) {
                DiagnosticsPanel(state)
            }
        }
    }

    if (settingsVisible) {
        SettingsSheet(
            settings = state.appSettings,
            onShowLogsChange = onShowLogsChange,
            onThemeModeChange = onThemeModeChange,
            onVpnModeChange = onVpnModeChange,
            onOpenAppPicker = {
                settingsVisible = false
                openAppPicker()
            },
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
            title = { Text("VPN mode") },
            text = { Text("нет выбранных приложений") },
        )
    }
}

@Composable
private fun ConnectionPanel(
    state: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val statusText = state.vpnState.status.label()
    val statusColor by animateColorAsState(
        targetValue = state.vpnState.status.statusColor(),
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

            ErrorMessage(state.vpnState.errorMessage)

            ConnectionActionButton(
                state = state,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
            )
        }
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
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(120),
        label = "connection-button-scale",
    )

    Button(
        onClick = if (state.canDisconnect) onDisconnect else onConnect,
        enabled = if (state.canDisconnect) {
            state.vpnState.status != VpnConnectionStatus.DISCONNECTING
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
            contentColor = Color.White,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
        Text(
            text = if (state.canDisconnect) "Disconnect" else "Connect",
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
    val clipboardManager = LocalClipboardManager.current
    val logText = state.vpnState.diagnostics.joinToString(separator = "\n")

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
                    IconButton(onClick = { clipboardManager.setText(AnnotatedString(logText)) }) {
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
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = logText,
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
    onThemeModeChange: (AppThemeMode) -> Unit,
    onVpnModeChange: (VpnMode) -> Unit,
    onOpenAppPicker: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.42f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline) },
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            SettingsSwitchRow(
                title = "Logs on main",
                checked = settings.showLogsOnMain,
                onCheckedChange = onShowLogsChange,
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
            }

            Box(modifier = Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable
private fun VpnModeSelector(
    selected: VpnMode,
    selectedAppsCount: Int,
    onSelected: (VpnMode) -> Unit,
    onOpenAppPicker: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VpnMode.values().forEach { mode ->
                VpnModeTile(
                    mode = mode,
                    selected = mode == selected,
                    onSelected = { onSelected(mode) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        AnimatedVisibility(visible = selected == VpnMode.SELECTED_APPS) {
            FilledTonalButton(
                onClick = onOpenAppPicker,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Default.Apps, contentDescription = null)
                Text(
                    "Select apps ($selectedAppsCount)",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun VpnModeTile(
    mode: VpnMode,
    selected: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(120),
        label = "vpn-mode-tile-scale",
    )
    val background by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        animationSpec = tween(180),
        label = "vpn-mode-tile-background",
    )

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
            },
        ),
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelected,
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = mode.icon(),
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = mode.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            AnimatedVisibility(visible = selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun ThemeModeSelector(
    selected: AppThemeMode,
    onSelected: (AppThemeMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppThemeMode.values().forEach { mode ->
            ThemeModeTile(
                mode = mode,
                selected = mode == selected,
                onSelected = { onSelected(mode) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ThemeModeTile(
    mode: AppThemeMode,
    selected: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(120),
        label = "theme-tile-scale",
    )
    val background by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        animationSpec = tween(180),
        label = "theme-tile-background",
    )

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
            },
        ),
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelected,
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = mode.icon(),
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = mode.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            AnimatedVisibility(visible = selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun GlassPanel(
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
        VpnConnectionStatus.CONNECTED -> Color(0xFF28C76F)
        VpnConnectionStatus.CONNECTING,
        VpnConnectionStatus.RECONNECTING
        -> MaterialTheme.colorScheme.primary
        VpnConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
        VpnConnectionStatus.DISCONNECTING -> MaterialTheme.colorScheme.tertiary
        VpnConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun AppThemeMode.icon(): ImageVector {
    return when (this) {
        AppThemeMode.SYSTEM -> Icons.Default.Settings
        AppThemeMode.LIGHT -> Icons.Default.LightMode
        AppThemeMode.DARK -> Icons.Default.DarkMode
    }
}

private fun VpnMode.icon(): ImageVector {
    return when (this) {
        VpnMode.PROXY -> Icons.Default.Settings
        VpnMode.SELECTED_APPS -> Icons.Default.Apps
    }
}
