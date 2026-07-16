package com.stansful.sshvpnclient.ui.smartconnect

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.R
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.AppUpdateDownloadState
import com.stansful.sshvpnclient.domain.model.AppUpdateInfo
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.SmartConnectPhase
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.XrayCoreAsset
import com.stansful.sshvpnclient.ui.common.AppScreen
import com.stansful.sshvpnclient.ui.common.AppSheetCornerRadius
import com.stansful.sshvpnclient.ui.common.AppUpdateAvailableDialog
import com.stansful.sshvpnclient.ui.common.AppUpdateSettingsSection
import com.stansful.sshvpnclient.ui.common.AppUpdateUiState
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import com.stansful.sshvpnclient.ui.common.InsetGroup
import com.stansful.sshvpnclient.ui.common.InsetRow
import com.stansful.sshvpnclient.ui.common.SectionHeader
import com.stansful.sshvpnclient.ui.common.SheetTitle
import com.stansful.sshvpnclient.ui.common.StatusCapsule
import com.stansful.sshvpnclient.ui.common.openAppUpdateInstaller
import com.stansful.sshvpnclient.ui.main.CustomThemeColorsEditor
import com.stansful.sshvpnclient.ui.settings.SettingsSwitchRow
import com.stansful.sshvpnclient.ui.settings.ThemeModeSelector
import com.stansful.sshvpnclient.ui.settings.VpnModeSelector
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SmartConnectRoute(
    container: AppContainer,
    openAppPicker: () -> Unit,
) {
    val viewModel: SmartConnectViewModel = viewModel(factory = AppViewModelFactory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var pendingUpdateInstallUri by remember { mutableStateOf<String?>(null) }

    // The ViewModel is activity-scoped and survives tab switches. Refresh on every route entry so
    // a core installed from OpenSource becomes usable here without an Activity restart.
    LaunchedEffect(viewModel) {
        viewModel.refreshXrayCoreAvailability()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshXrayCoreAvailability()
    }

    LaunchedEffect(
        state.workflow.desiredActive,
        state.ownsVpnSession,
        state.isStartPending,
    ) {
        if (state.workflow.desiredActive && !state.ownsVpnSession && !state.isStartPending) {
            viewModel.reconcilePersistedSession(
                vpnPermissionGranted = VpnService.prepare(context) == null,
            )
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.start()
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

    val requestStart = {
        if (viewModel.prepareStart()) {
            val permissionIntent = VpnService.prepare(context)
            if (permissionIntent == null) {
                viewModel.start()
            } else {
                vpnPermissionLauncher.launch(permissionIntent)
            }
        }
        Unit
    }

    SmartConnectScreen(
        state = state,
        onStart = requestStart,
        onStop = viewModel::stop,
        onShowLogsChange = viewModel::setShowLogsOnSmartConnect,
        onThemeModeChange = viewModel::setThemeMode,
        onCustomThemeColorsChange = viewModel::setCustomThemeColors,
        onVpnModeChange = viewModel::setVpnMode,
        onCheckXrayCoreUpdates = viewModel::checkXrayCoreUpdates,
        onDownloadXrayCore = viewModel::downloadXrayCore,
        onCancelXrayCoreDownload = viewModel::cancelXrayCoreDownload,
        onCheckForUpdates = viewModel::checkForUpdates,
        onDismissUpdate = viewModel::dismissAvailableUpdate,
        onOpenUpdateRelease = { update -> uriHandler.openUri(update.releaseUrl) },
        onDownloadUpdate = viewModel::downloadAvailableUpdate,
        onInstallUpdate = requestUpdateInstall,
        openAppPicker = openAppPicker,
        onDismissNoSelectedApps = viewModel::dismissNoSelectedAppsDialog,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartConnectScreen(
    state: SmartConnectUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShowLogsChange: (Boolean) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onCustomThemeColorsChange: (CustomThemeColors) -> Unit,
    onVpnModeChange: (VpnMode) -> Unit,
    onCheckXrayCoreUpdates: () -> Unit,
    onDownloadXrayCore: (XrayCoreAsset) -> Unit,
    onCancelXrayCoreDownload: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDismissUpdate: () -> Unit,
    onOpenUpdateRelease: (AppUpdateInfo) -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    openAppPicker: () -> Unit,
    onDismissNoSelectedApps: () -> Unit,
) {
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var routesVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.updateState.availableUpdate) {
        if (state.updateState.availableUpdate != null) {
            settingsVisible = false
        }
    }

    AppScreen(
        title = stringResource(R.string.smart_connect_title),
        actions = {
            IconButton(onClick = { settingsVisible = true }) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.smart_connect_open_settings),
                )
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "route-selector") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(title = stringResource(R.string.smart_connect_automatic_route))
                    SmartRouteSelector(
                        state = state,
                        onClick = { routesVisible = true },
                    )
                }
            }

            item(key = "power-control") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SmartConnectPowerButton(
                        state = state,
                        onClick = if (state.isActive) onStop else onStart,
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionHeader(title = "Connection status")
                        SmartWorkflowStatus(state)
                    }
                }
            }

            if (!state.xrayCoreAvailable) {
                item(key = "xray-core-action") {
                    FilledTonalButton(
                        onClick = { settingsVisible = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Text(
                            text = stringResource(R.string.smart_connect_manage_xray_core),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }

            if (state.appSettings.showLogsOnSmartConnect && state.vpnState.diagnostics.isNotEmpty()) {
                item(key = "diagnostics") {
                    SmartDiagnosticsPanel(state.vpnState.diagnostics)
                }
            }
        }
    }

    if (routesVisible) {
        SmartRoutesSheet(
            profiles = state.rankedProfiles,
            selectedProfileId = state.selectedProfile?.id,
            onDismiss = { routesVisible = false },
        )
    }

    if (settingsVisible) {
        SmartConnectSettingsSheet(
            settings = state.appSettings,
            xrayCoreAvailable = state.xrayCoreAvailable,
            xrayCoreUpdate = state.xrayCoreUpdate,
            xrayRuntimeInUse = state.xrayRuntimeInUse,
            onShowLogsChange = onShowLogsChange,
            onThemeModeChange = onThemeModeChange,
            onCustomThemeColorsChange = onCustomThemeColorsChange,
            onVpnModeChange = onVpnModeChange,
            onCheckXrayCoreUpdates = onCheckXrayCoreUpdates,
            onDownloadXrayCore = onDownloadXrayCore,
            onCancelXrayCoreDownload = onCancelXrayCoreDownload,
            updateState = state.updateState,
            onCheckForUpdates = onCheckForUpdates,
            onResumeUpdate = onDownloadUpdate,
            onInstallUpdate = onInstallUpdate,
            onOpenAppPicker = {
                settingsVisible = false
                openAppPicker()
            },
            onDismiss = { settingsVisible = false },
        )
    }

    state.updateState.availableUpdate?.let { update ->
        AppUpdateAvailableDialog(
            update = update,
            downloadState = state.updateState.downloadState,
            onLater = onDismissUpdate,
            onOpenRelease = { onOpenUpdateRelease(update) },
            onDownload = {
                onDownloadUpdate()
                settingsVisible = true
            },
            onInstall = onInstallUpdate,
        )
    }

    if (state.showNoSelectedAppsDialog) {
        AlertDialog(
            onDismissRequest = onDismissNoSelectedApps,
            title = { Text(stringResource(R.string.vpn_mode)) },
            text = { Text(stringResource(R.string.error_no_selected_apps)) },
            confirmButton = {
                TextButton(onClick = onDismissNoSelectedApps) {
                    Text(stringResource(R.string.smart_connect_ok))
                }
            },
        )
    }
}

@Composable
private fun SmartRouteSelector(
    state: SmartConnectUiState,
    onClick: () -> Unit,
) {
    val profile = state.selectedProfile
    val profileName = state.workflow.activeProfileName ?: profile?.name
    val latencyMs = state.workflow.activeProfileLatencyMs ?: profile?.lastLatencyMs

    InsetGroup(contentPadding = PaddingValues(0.dp)) {
        InsetRow(
            title = profileName ?: stringResource(R.string.smart_connect_no_available_routes),
            subtitle = pluralStringResource(
                R.plurals.smart_connect_available_routes,
                state.rankedProfiles.size,
                state.rankedProfiles.size,
            ),
            icon = Icons.Default.AutoAwesome,
            onClick = onClick.takeIf { state.rankedProfiles.isNotEmpty() },
            trailing = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    latencyMs?.let { latency -> LatencyBadge(latency) }
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

@Composable
private fun SmartConnectPowerButton(
    state: SmartConnectUiState,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = tween(120),
        label = "smart-connect-power-scale",
    )
    val statusColor = state.workflow.phase.statusColor(state.isActive)
    val enabled = state.isActive || state.canStart
    val phaseDescription = state.workflow.phase.phaseLabel()
    val progress = state.checkingProgress
        ?.takeIf { state.workflow.phase == SmartConnectPhase.CHECKING }

    Box(
        modifier = Modifier.size(156.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (progress != null) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                color = statusColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 3.dp,
            )
        }

        Surface(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = Modifier
                .size(144.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .semantics {
                    role = Role.Button
                    stateDescription = phaseDescription
                },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = statusColor,
            border = BorderStroke(1.5.dp, statusColor.copy(alpha = if (enabled) 0.82f else 0.38f)),
            shadowElevation = if (state.isActive) 2.dp else 0.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = stringResource(
                        if (state.isActive) R.string.smart_connect_stop else R.string.smart_connect_start,
                    ),
                    modifier = Modifier.padding(top = 7.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SmartWorkflowStatus(state: SmartConnectUiState) {
    InsetGroup {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            val statusColor = state.workflow.phase.statusColor(state.isActive)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.smart_connect_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                StatusCapsule(
                    text = state.workflow.phase.phaseLabel(),
                    color = statusColor,
                )
            }

            if (state.workflow.phase == SmartConnectPhase.CHECKING && state.workflow.checkTotal > 0) {
                LinearProgressIndicator(
                    progress = { state.checkingProgress ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.smart_connect_checking_progress,
                        state.workflow.checkCompleted,
                        state.workflow.checkTotal,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.workflow.catalogSize > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.smart_connect_catalog_summary,
                        state.workflow.catalogSize,
                        state.workflow.availableCount,
                        state.workflow.catalogSize,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.workflow.lastHealthLatencyMs?.let { latency ->
                Text(
                    text = stringResource(R.string.smart_connect_health_latency, latency),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.workflow.retryDelayMs?.let { delayMs ->
                Text(
                    text = stringResource(
                        R.string.smart_connect_retry_delay,
                        (delayMs / 1_000L).coerceAtLeast(1L),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.visibleMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.workflow.phase == SmartConnectPhase.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (!state.xrayCoreAvailable) {
                Text(
                    text = stringResource(R.string.smart_connect_xray_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartRoutesSheet(
    profiles: List<ProxyProfileSummary>,
    selectedProfileId: String?,
    onDismiss: () -> Unit,
) {
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
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SheetTitle(
                title = stringResource(R.string.smart_connect_available_routes_title),
                subtitle = stringResource(R.string.smart_connect_routes_ranked_hint),
            )
            InsetGroup(contentPadding = PaddingValues(0.dp)) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 430.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    itemsIndexed(
                        items = profiles,
                        key = { _, profile -> profile.id },
                    ) { index, profile ->
                        SmartRouteRow(
                            profile = profile,
                            selected = profile.id == selectedProfileId,
                        )
                        if (index < profiles.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartRouteRow(
    profile: ProxyProfileSummary,
    selected: Boolean,
) {
    InsetRow(
        title = profile.name,
        subtitle = "${profile.protocol.scheme} · ${profile.host}:${profile.port}",
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.smart_connect_selected_route),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                profile.lastLatencyMs?.let { latency -> LatencyBadge(latency) }
            }
        },
    )
}

@Composable
private fun LatencyBadge(latencyMs: Long) {
    val color = when {
        latencyMs <= FAST_LATENCY_MS -> MaterialTheme.colorScheme.secondary
        latencyMs <= ACCEPTABLE_LATENCY_MS -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    StatusCapsule(
        text = stringResource(R.string.smart_connect_latency, latencyMs),
        color = color,
    )
}

@Composable
private fun SmartDiagnosticsPanel(diagnostics: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title = stringResource(R.string.connection_diagnostics))
        InsetGroup {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.connection_diagnostics),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.diagnostics_line_count,
                                diagnostics.size,
                                diagnostics.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText(
                                            "Smart Connect diagnostics",
                                            diagnostics.joinToString(separator = "\n"),
                                        ),
                                    ),
                                )
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.copy_diagnostics),
                        )
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) R.string.hide_diagnostics else R.string.show_diagnostics,
                            ),
                        )
                    }
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(140)),
                    exit = fadeOut(tween(100)),
                ) {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 260.dp),
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
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartConnectSettingsSheet(
    settings: AppSettings,
    xrayCoreAvailable: Boolean,
    xrayCoreUpdate: SmartXrayCoreUpdateUiState,
    xrayRuntimeInUse: Boolean,
    onShowLogsChange: (Boolean) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onCustomThemeColorsChange: (CustomThemeColors) -> Unit,
    onVpnModeChange: (VpnMode) -> Unit,
    onCheckXrayCoreUpdates: () -> Unit,
    onDownloadXrayCore: (XrayCoreAsset) -> Unit,
    onCancelXrayCoreDownload: () -> Unit,
    updateState: AppUpdateUiState,
    onCheckForUpdates: () -> Unit,
    onResumeUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenAppPicker: () -> Unit,
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
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SheetTitle(
                    title = stringResource(R.string.settings),
                    subtitle = stringResource(R.string.smart_connect_title),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(title = "General")
                    InsetGroup(contentPadding = PaddingValues(0.dp)) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.debug_logs),
                            checked = settings.showLogsOnSmartConnect,
                            onCheckedChange = onShowLogsChange,
                        )
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(title = stringResource(R.string.smart_connect_xray_core_title))
                    InsetGroup {
                        SmartXrayCoreUpdateSection(
                            xrayCoreAvailable = xrayCoreAvailable,
                            state = xrayCoreUpdate,
                            xrayRuntimeInUse = xrayRuntimeInUse,
                            onCheckUpdates = onCheckXrayCoreUpdates,
                            onDownload = onDownloadXrayCore,
                            onCancelDownload = onCancelXrayCoreDownload,
                        )
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(title = stringResource(R.string.vpn_mode))
                    InsetGroup {
                        VpnModeSelector(
                            selected = settings.vpnMode,
                            selectedAppsCount = settings.selectedAppPackages.size,
                            onSelected = onVpnModeChange,
                            onOpenAppPicker = onOpenAppPicker,
                            deferEmptySelectedAppsMode = true,
                        )
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(title = stringResource(R.string.theme))
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
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(title = "Updates")
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
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(title = "About")
                    InsetGroup(contentPadding = PaddingValues(0.dp)) {
                        SmartGitHubRow(
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
                }
            }
        }
    }
}

@Composable
private fun SmartXrayCoreUpdateSection(
    xrayCoreAvailable: Boolean,
    state: SmartXrayCoreUpdateUiState,
    xrayRuntimeInUse: Boolean,
    onCheckUpdates: () -> Unit,
    onDownload: (XrayCoreAsset) -> Unit,
    onCancelDownload: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (xrayCoreAvailable) {
                stringResource(R.string.smart_connect_xray_core_installed, state.runtimeAbi)
            } else {
                stringResource(R.string.smart_connect_xray_core_not_installed, state.runtimeAbi)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = onCheckUpdates,
            enabled = !state.isChecking && !state.isDownloading,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            if (state.isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null)
            }
            Text(
                text = stringResource(
                    if (state.isChecking) {
                        R.string.smart_connect_xray_core_checking
                    } else {
                        R.string.smart_connect_xray_core_check_updates
                    },
                ),
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        state.release?.let { release ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.smart_connect_xray_core_release,
                            release.versionName,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            R.string.smart_connect_xray_core_compatible,
                            release.runtimeAbi,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { uriHandler.openUri(release.releaseUrl) }) {
                    Text(stringResource(R.string.smart_connect_xray_core_open_release))
                }
            }

            SmartXrayCoreAssetRow(
                asset = state.compatibleAsset,
                runtimeAbi = state.runtimeAbi,
                downloading = state.isDownloading && state.downloadingAbi == state.runtimeAbi,
                enabled = !xrayRuntimeInUse && !state.isChecking && !state.isDownloading,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
            )
        }

        state.statusMessage?.let { message ->
            val isError = message.contains("failed", ignoreCase = true) ||
                message.contains("unable", ignoreCase = true) ||
                message.contains("no ", ignoreCase = true) ||
                message.contains("disconnect", ignoreCase = true) ||
                message.contains("not compatible", ignoreCase = true)
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (xrayRuntimeInUse) {
            Text(
                text = stringResource(R.string.smart_connect_xray_core_disconnect),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SmartXrayCoreAssetRow(
    asset: XrayCoreAsset?,
    runtimeAbi: String,
    downloading: Boolean,
    enabled: Boolean,
    onDownload: (XrayCoreAsset) -> Unit,
    onCancelDownload: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = runtimeAbi,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = when {
                        asset == null -> stringResource(R.string.smart_connect_xray_core_asset_missing)
                        asset.universal -> stringResource(
                            R.string.smart_connect_xray_core_asset_universal,
                            formatSmartFileSize(asset.sizeBytes),
                        )
                        else -> stringResource(
                            R.string.smart_connect_xray_core_asset_named,
                            asset.name,
                            formatSmartFileSize(asset.sizeBytes),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(
                onClick = {
                    if (downloading) {
                        onCancelDownload()
                    } else {
                        asset?.let(onDownload)
                    }
                },
                enabled = downloading || (asset != null && enabled),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = if (downloading) Icons.Default.Cancel else Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(
                        when {
                            downloading -> R.string.smart_connect_xray_core_cancel
                            asset == null -> R.string.smart_connect_xray_core_missing
                            else -> R.string.smart_connect_xray_core_download
                        },
                    ),
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun SmartGitHubRow(
    onClick: () -> Unit,
    onCopyClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github_mark),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.smart_connect_github),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.smart_connect_github_repository),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCopyClick) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.smart_connect_copy_github),
                )
            }
        }
    }
}

@Composable
private fun SmartConnectPhase.phaseLabel(): String {
    return stringResource(
        when (this) {
            SmartConnectPhase.IDLE -> R.string.smart_connect_phase_idle
            SmartConnectPhase.STARTING -> R.string.smart_connect_phase_starting
            SmartConnectPhase.WAITING_FOR_NETWORK -> R.string.smart_connect_phase_waiting_network
            SmartConnectPhase.REFRESHING -> R.string.smart_connect_phase_refreshing
            SmartConnectPhase.CHECKING -> R.string.smart_connect_phase_checking
            SmartConnectPhase.CLEANING -> R.string.smart_connect_phase_cleaning
            SmartConnectPhase.SELECTING -> R.string.smart_connect_phase_selecting
            SmartConnectPhase.CONNECTING -> R.string.smart_connect_phase_connecting
            SmartConnectPhase.VERIFYING -> R.string.smart_connect_phase_verifying
            SmartConnectPhase.CONNECTED -> R.string.smart_connect_phase_connected
            SmartConnectPhase.FAILING_OVER -> R.string.smart_connect_phase_failover
            SmartConnectPhase.RETRY_WAIT -> R.string.smart_connect_phase_retry_wait
            SmartConnectPhase.STOPPING -> R.string.smart_connect_phase_stopping
            SmartConnectPhase.ERROR -> R.string.smart_connect_phase_error
        },
    )
}

@Composable
private fun SmartConnectPhase.statusColor(active: Boolean): Color {
    return when (this) {
        SmartConnectPhase.CONNECTED,
        SmartConnectPhase.VERIFYING,
        -> MaterialTheme.colorScheme.secondary

        SmartConnectPhase.ERROR -> MaterialTheme.colorScheme.error
        SmartConnectPhase.STOPPING -> MaterialTheme.colorScheme.tertiary
        SmartConnectPhase.IDLE -> if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        else -> MaterialTheme.colorScheme.primary
    }
}

private const val FAST_LATENCY_MS = 200L
private const val ACCEPTABLE_LATENCY_MS = 500L
private const val GITHUB_REPOSITORY_URL =
    "https://github.com/stansful/ssh-vpn-client-kotlin/tree/master"

private fun formatSmartFileSize(sizeBytes: Long): String {
    val mebibytes = sizeBytes.toDouble() / (1_024.0 * 1_024.0)
    return String.format(Locale.US, "%.1f MiB", mebibytes)
}
