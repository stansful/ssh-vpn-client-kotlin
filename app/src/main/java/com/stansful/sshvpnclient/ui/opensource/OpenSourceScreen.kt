package com.stansful.sshvpnclient.ui.opensource

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.XrayCoreAsset
import com.stansful.sshvpnclient.ui.common.AppUpdateAvailableDialog
import com.stansful.sshvpnclient.ui.common.AppUpdateSettingsSection
import com.stansful.sshvpnclient.ui.common.AppUpdateUiState
import com.stansful.sshvpnclient.ui.common.AppScreen
import com.stansful.sshvpnclient.ui.common.AppSheetCornerRadius
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import com.stansful.sshvpnclient.ui.common.EmptyState
import com.stansful.sshvpnclient.ui.common.InsetGroup
import com.stansful.sshvpnclient.ui.common.InsetRow
import com.stansful.sshvpnclient.ui.common.SectionHeader
import com.stansful.sshvpnclient.ui.common.SheetTitle
import com.stansful.sshvpnclient.ui.common.StatusCapsule
import com.stansful.sshvpnclient.ui.common.formatFileSize
import com.stansful.sshvpnclient.ui.common.openAppUpdateInstaller
import com.stansful.sshvpnclient.ui.main.CustomThemeColorsEditor
import com.stansful.sshvpnclient.ui.settings.SettingsSwitchRow
import com.stansful.sshvpnclient.ui.settings.ThemeModeSelector
import com.stansful.sshvpnclient.ui.settings.VpnModeSelector
import com.stansful.sshvpnclient.work.ProxySourceSyncWorker
import kotlinx.coroutines.launch

@Composable
fun OpenSourceRoute(
    container: AppContainer,
    openAppPicker: () -> Unit,
) {
    val viewModel: OpenSourceViewModel = viewModel(factory = AppViewModelFactory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var pendingUpdateInstallUri by remember { mutableStateOf<String?>(null) }

    // Both global tabs keep activity-scoped ViewModels. Re-read the process-wide bridge whenever
    // this route enters composition so an installation completed from Smart Connect is visible
    // immediately instead of waiting for the Activity to be recreated.
    LaunchedEffect(viewModel) {
        viewModel.refreshXrayCoreAvailability()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshXrayCoreAvailability()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.cancelChecks()
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelChecks() }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.connect()
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

    OpenSourceScreen(
        state = state,
        viewModel = viewModel,
        openAppPicker = openAppPicker,
        onConnect = {
            val permissionIntent = VpnService.prepare(context)
            if (permissionIntent == null) viewModel.connect() else vpnPermissionLauncher.launch(permissionIntent)
        },
        onOpenUpdateRelease = { update -> uriHandler.openUri(update.releaseUrl) },
        onInstallUpdate = requestUpdateInstall,
        onAutoUpdateChange = { enabled ->
            viewModel.setOpenSourceAutoUpdateEnabled(enabled)
            if (enabled) {
                ProxySourceSyncWorker.schedule(container.applicationContext)
            } else {
                ProxySourceSyncWorker.cancel(container.applicationContext)
            }
        },
    )
}

@Composable
private fun OpenSourceScreen(
    state: OpenSourceUiState,
    viewModel: OpenSourceViewModel,
    openAppPicker: () -> Unit,
    onConnect: () -> Unit,
    onOpenUpdateRelease: (AppUpdateInfo) -> Unit,
    onInstallUpdate: () -> Unit,
    onAutoUpdateChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    var pendingDeleteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var settingsVisible by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var topMenuExpanded by remember { mutableStateOf(false) }

    AppScreen(
        title = if (state.selectionMode) "Selected: ${state.selectedIds.size}" else "Public Routes",
        actions = {
            if (state.selectionMode) {
                IconButton(onClick = viewModel::selectAll) {
                    Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                }
                IconButton(onClick = { pendingDeleteIds = state.selectedIds }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                }
                IconButton(onClick = viewModel::clearSelection) {
                    Icon(Icons.Default.Close, contentDescription = "Clear selection")
                }
            } else {
                IconButton(
                    onClick = {
                        searchVisible = !searchVisible
                        if (!searchVisible) viewModel.setQuery("")
                    },
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Search configurations")
                }
                IconButton(onClick = { settingsVisible = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
                Box {
                    IconButton(onClick = { topMenuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(
                        expanded = topMenuExpanded,
                        onDismissRequest = { topMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Add configuration") },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = {
                                topMenuExpanded = false
                                viewModel.openEditor()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Import from clipboard") },
                            leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                            onClick = {
                                topMenuExpanded = false
                                viewModel.showBulkImport()
                            },
                        )
                    }
                }
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RiskBanner(
                expanded = state.appSettings.openSourceRiskBannerExpanded,
                onExpandedChange = viewModel::setOpenSourceRiskBannerExpanded,
            )
            OpenSourceActions(
                state = state,
                onRefresh = { viewModel.synchronize(force = true) },
                onCheckSelected = viewModel::checkSelected,
                onCheckAll = viewModel::checkAll,
                onCancelChecks = viewModel::cancelChecks,
                onRemoveUnavailable = viewModel::requestRemoveUnavailable,
                onConnect = if (state.xrayConnected) viewModel::disconnect else onConnect,
            )
            AnimatedVisibility(visible = searchVisible || state.query.isNotBlank() || state.pinnedOnly) {
                InsetGroup(contentPadding = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            label = { Text("Search configurations") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        viewModel.setQuery("")
                                        viewModel.setPinnedOnly(false)
                                        searchVisible = false
                                    },
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close search")
                                }
                            },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FilterChip(
                            selected = state.pinnedOnly,
                            onClick = { viewModel.setPinnedOnly(!state.pinnedOnly) },
                            label = { Text("Pinned only") },
                            leadingIcon = if (state.pinnedOnly) {
                                { Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = state.appSettings.showLogsOnOpenSource && state.vpnState.diagnostics.isNotEmpty(),
                enter = fadeIn() + slideInVertically { it / 4 },
                exit = fadeOut(),
            ) {
                DiagnosticsLogPanel(state.vpnState.diagnostics)
            }
            if (state.profiles.isEmpty()) {
                EmptyProxyList(onAdd = { viewModel.openEditor() })
            } else {
                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = if (state.profiles.size > SCROLL_JUMP_THRESHOLD) 72.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.profiles, key = ProxyProfileSummary::id) { profile ->
                            ProxyProfileCard(
                                profile = profile,
                                checked = profile.id in state.selectedIds,
                                selectionMode = state.selectionMode,
                                hostPingMs = state.hostPingMs[profile.id],
                                onClick = { viewModel.selectProfile(profile.id) },
                                onLongClick = { viewModel.beginBulkSelection(profile.id) },
                                onCopy = {
                                    coroutineScope.launch {
                                        val rawUri = viewModel.rawUri(profile.id)
                                        clipboard.setClipEntry(
                                            ClipEntry(sensitiveClipData("Proxy configuration", rawUri)),
                                        )
                                    }
                                },
                                onEdit = { viewModel.openEditor(profile.id) },
                                onDelete = { pendingDeleteIds = setOf(profile.id) },
                                onPinChange = { pinned -> viewModel.setPinned(profile.id, pinned) },
                            )
                        }
                    }
                    if (state.profiles.size > SCROLL_JUMP_THRESHOLD) {
                        ScrollJumpButtons(
                            onScrollToTop = {
                                coroutineScope.launch { listState.animateScrollToItem(0) }
                            },
                            onScrollToBottom = {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(state.profiles.lastIndex)
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 8.dp, bottom = 8.dp),
                        )
                    }
                }
            }
        }
    }

    state.editor?.let { editor ->
        ProfileEditorDialog(
            editor = editor,
            onValueChange = viewModel::updateEditor,
            onSave = viewModel::saveEditor,
            onDismiss = viewModel::dismissEditor,
        )
    }
    if (state.showBulkImport) {
        val clipboardText = remember {
            context.getSystemService(ClipboardManager::class.java)
                ?.primaryClip
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                .orEmpty()
        }
        BulkImportDialog(
            initialText = clipboardText,
            onImport = viewModel::importClipboard,
            onDismiss = viewModel::dismissBulkImport,
        )
    }
    if (pendingDeleteIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingDeleteIds = emptySet() },
            title = { Text("Delete configurations") },
            text = { Text("Delete ${pendingDeleteIds.size} selected configuration(s)?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pendingDeleteIds == state.selectedIds) {
                            viewModel.deleteSelected()
                        } else {
                            pendingDeleteIds.forEach(viewModel::deleteProfile)
                        }
                        pendingDeleteIds = emptySet()
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteIds = emptySet() }) { Text("Cancel") }
            },
        )
    }
    if (state.showRemoveUnavailableConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRemoveUnavailableConfirmation,
            title = { Text(stringResource(R.string.open_source_remove_unavailable_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.open_source_remove_unavailable_confirmation,
                        state.unavailableUnpinnedCount,
                        state.unavailableUnpinnedCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::removeUnavailableExceptPinned) {
                    Text(stringResource(R.string.open_source_remove_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRemoveUnavailableConfirmation) {
                    Text(stringResource(R.string.open_source_remove_cancel))
                }
            },
        )
    }
    if (settingsVisible) {
        OpenSourceSettingsSheet(
            settings = state.appSettings,
            onShowLogsChange = viewModel::setShowLogsOnOpenSource,
            onShowWarningDialogChange = viewModel::setShowOpenSourceWarningOnEnter,
            onThemeModeChange = viewModel::setThemeMode,
            onCustomThemeColorsChange = viewModel::setCustomThemeColors,
            onVpnModeChange = viewModel::setVpnMode,
            onOpenAppPicker = {
                settingsVisible = false
                openAppPicker()
            },
            onAutoUpdateChange = onAutoUpdateChange,
            xrayCoreAvailable = state.xrayCoreAvailable,
            xrayCoreUpdateState = state.xrayCoreUpdateState,
            xrayActive = state.anyXrayRuntimeActive,
            onCheckXrayCoreUpdates = viewModel::checkXrayCoreUpdates,
            onDownloadXrayCore = viewModel::downloadXrayCore,
            onCancelXrayCoreDownload = viewModel::cancelXrayCoreDownload,
            updateState = state.updateState,
            onCheckForUpdates = viewModel::checkForUpdates,
            onResumeUpdate = viewModel::downloadAvailableUpdate,
            onInstallUpdate = onInstallUpdate,
            onDismiss = { settingsVisible = false },
        )
    }
    state.updateState.availableUpdate?.let { update ->
        AppUpdateAvailableDialog(
            update = update,
            downloadState = state.updateState.downloadState,
            onLater = viewModel::dismissAvailableUpdate,
            onOpenRelease = { onOpenUpdateRelease(update) },
            onDownload = {
                viewModel.downloadAvailableUpdate()
                settingsVisible = true
            },
            onInstall = onInstallUpdate,
        )
    }
    if (state.showNoSelectedAppsDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissNoSelectedAppsDialog,
            confirmButton = {
                TextButton(onClick = viewModel::dismissNoSelectedAppsDialog) {
                    Text("OK")
                }
            },
            title = { Text(stringResource(R.string.vpn_mode)) },
            text = { Text(stringResource(R.string.error_no_selected_apps)) },
        )
    }
}

@Composable
private fun ScrollJumpButtons(
    onScrollToTop: () -> Unit,
    onScrollToBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onScrollToTop, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Scroll to top")
            }
            FilledTonalIconButton(onClick = onScrollToBottom, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom")
            }
        }
    }
}

@Composable
private fun RiskBanner(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    InsetGroup(contentPadding = PaddingValues(0.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
        ) {
            InsetRow(
                title = stringResource(R.string.open_source_banner_title),
                subtitle = if (expanded) null else "Review before using public routes",
                onClick = { onExpandedChange(!expanded) },
                trailing = {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) {
                            stringResource(R.string.hide_diagnostics)
                        } else {
                            stringResource(R.string.show_diagnostics)
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                },
            )
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = stringResource(R.string.open_source_warning_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsLogPanel(diagnostics: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    InsetGroup {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.connection_diagnostics),
                        style = MaterialTheme.typography.labelLarge,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText(
                                            "Connection diagnostics",
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
                            contentDescription = if (expanded) {
                                stringResource(R.string.hide_diagnostics)
                            } else {
                                stringResource(R.string.show_diagnostics)
                            },
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 280.dp),
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
private fun OpenSourceSettingsSheet(
    settings: AppSettings,
    onShowLogsChange: (Boolean) -> Unit,
    onShowWarningDialogChange: (Boolean) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onCustomThemeColorsChange: (CustomThemeColors) -> Unit,
    onVpnModeChange: (VpnMode) -> Unit,
    onOpenAppPicker: () -> Unit,
    onAutoUpdateChange: (Boolean) -> Unit,
    xrayCoreAvailable: Boolean,
    xrayCoreUpdateState: XrayCoreUpdateUiState,
    xrayActive: Boolean,
    onCheckXrayCoreUpdates: () -> Unit,
    onDownloadXrayCore: (XrayCoreAsset) -> Unit,
    onCancelXrayCoreDownload: () -> Unit,
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
                title = stringResource(R.string.settings),
                subtitle = "Public route connection and update preferences",
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(title = "General")
                InsetGroup(contentPadding = PaddingValues(0.dp)) {
                    Column {
                        SettingsSwitchRow(
                            title = stringResource(R.string.debug_logs),
                            checked = settings.showLogsOnOpenSource,
                            onCheckedChange = onShowLogsChange,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                        )
                        SettingsSwitchRow(
                            title = stringResource(R.string.show_warning_dialog),
                            checked = settings.showOpenSourceWarningOnEnter,
                            onCheckedChange = onShowWarningDialogChange,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                        )
                        SettingsSwitchRow(
                            title = "Auto-refresh public configurations",
                            checked = settings.openSourceAutoUpdateEnabled,
                            onCheckedChange = onAutoUpdateChange,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(title = "Xray core")
                InsetGroup {
                    XrayCoreUpdateSection(
                        xrayCoreAvailable = xrayCoreAvailable,
                        state = xrayCoreUpdateState,
                        xrayActive = xrayActive,
                        onCheckUpdates = onCheckXrayCoreUpdates,
                        onDownload = onDownloadXrayCore,
                        onCancelDownload = onCancelXrayCoreDownload,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(title = "About")
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
private fun XrayCoreUpdateSection(
    xrayCoreAvailable: Boolean,
    state: XrayCoreUpdateUiState,
    xrayActive: Boolean,
    onCheckUpdates: () -> Unit,
    onDownload: (XrayCoreAsset) -> Unit,
    onCancelDownload: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (xrayCoreAvailable) {
                "Installed for this app. Runtime ABI: ${state.runtimeAbi}"
            } else {
                "Not installed. Download the official libXray core for ${state.runtimeAbi}."
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
                text = if (state.isChecking) "Checking core updates" else "Check Xray core updates",
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        state.release?.let { release ->
            val runtimeAsset = release.assets.firstOrNull { asset -> asset.abi == release.runtimeAbi }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Release ${release.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Compatible core for ${release.runtimeAbi}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { uriHandler.openUri(release.releaseUrl) }) {
                    Text("Open")
                }
            }

            XrayCoreAssetRow(
                asset = runtimeAsset,
                runtimeAbi = state.runtimeAbi,
                downloading = state.isDownloading && state.downloadingAbi == state.runtimeAbi,
                enabled = !xrayActive && !state.isChecking && !state.isDownloading,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
            )
        }

        state.statusMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (message.contains("failed", ignoreCase = true) ||
                    message.contains("unable", ignoreCase = true) ||
                    message.contains("no ", ignoreCase = true)
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (xrayActive) {
            Text(
                text = "Disconnect the active Xray VPN before updating the Xray core.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun XrayCoreAssetRow(
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
                        asset == null -> "No compatible Xray core asset is published for this release"
                        asset.universal -> "Universal AAR · ${formatFileSize(asset.sizeBytes)}"
                        else -> "${asset.name} · ${formatFileSize(asset.sizeBytes)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
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
                if (downloading) {
                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(
                    text = when {
                        downloading -> "Cancel"
                        asset == null -> "Missing"
                        else -> "Download"
                    },
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun GitHubLinkRow(
    onClick: () -> Unit,
    onCopyClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    InsetGroup(contentPadding = PaddingValues(0.dp)) {
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
private fun OpenSourceActions(
    state: OpenSourceUiState,
    onRefresh: () -> Unit,
    onCheckSelected: () -> Unit,
    onCheckAll: () -> Unit,
    onCancelChecks: () -> Unit,
    onRemoveUnavailable: () -> Unit,
    onConnect: () -> Unit,
) {
    val statusText = when {
        state.xrayConnected -> "Connected"
        state.sshActive -> "SSH active"
        state.vpnState.status == VpnConnectionStatus.ERROR -> "Error"
        state.isSyncing -> "Refreshing"
        else -> "Ready"
    }
    val statusColor = when {
        state.xrayConnected -> MaterialTheme.colorScheme.secondary
        state.vpnState.status == VpnConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
        state.sshActive || state.isSyncing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val refreshColor = MaterialTheme.colorScheme.primary
    val refreshEnabled = !state.isSyncing && !state.isRemovingUnavailable
    val checkAllColor = if (state.isChecking) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val checkAllEnabled = (state.profiles.isNotEmpty() || state.isChecking) &&
        state.xrayCoreAvailable &&
        !state.isRemovingUnavailable

    InsetGroup {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SectionHeader(title = "Connection")
                    Text(
                        text = state.selectedProfile?.name ?: "Select a route below",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.selectedProfile?.let { profile ->
                        Text(
                            text = "${profile.protocol.scheme} · ${profile.host}:${profile.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                StatusCapsule(text = statusText, color = statusColor)
            }

            Button(
                onClick = onConnect,
                enabled = state.xrayConnected || state.canStartOpenSource,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.xrayConnected) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentColor = if (state.xrayConnected) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                ),
                contentPadding = ButtonDefaults.ContentPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                Text(
                    when {
                        state.xrayConnected -> "Disconnect"
                        state.sshActive -> "Switch to Public Routes"
                        else -> "Connect"
                    },
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
            SectionHeader(title = "Route tools")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRefresh,
                    enabled = refreshEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = refreshColor.copy(alpha = 0.16f),
                        contentColor = refreshColor,
                        disabledContainerColor = refreshColor.copy(alpha = 0.08f),
                        disabledContentColor = refreshColor.copy(alpha = 0.55f),
                    ),
                    border = BorderStroke(
                        1.dp,
                        refreshColor.copy(alpha = if (refreshEnabled) 0.42f else 0.18f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (state.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = refreshColor,
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        "Refresh",
                        modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Button(
                    onClick = if (state.isChecking) onCancelChecks else onCheckAll,
                    enabled = checkAllEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = checkAllColor.copy(alpha = 0.16f),
                        contentColor = checkAllColor,
                        disabledContainerColor = checkAllColor.copy(alpha = 0.08f),
                        disabledContentColor = checkAllColor.copy(alpha = 0.55f),
                    ),
                    border = BorderStroke(
                        1.dp,
                        checkAllColor.copy(alpha = if (checkAllEnabled) 0.42f else 0.18f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(
                        if (state.isChecking) Icons.Default.Cancel else Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        if (state.isChecking) "Cancel" else "Check all",
                        modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            state.selectedProfile?.let {
                CheckSelectedButton(
                    state = state,
                    onCheckSelected = onCheckSelected,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            AnimatedVisibility(
                visible = state.unavailableUnpinnedCount > 0 || state.isRemovingUnavailable,
            ) {
                TextButton(
                    onClick = onRemoveUnavailable,
                    enabled = state.canRemoveUnavailable,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    contentPadding = ButtonDefaults.ContentPadding,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (state.isRemovingUnavailable) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.Delete, contentDescription = null)
                    }
                    Text(
                        text = if (state.isRemovingUnavailable) {
                            stringResource(R.string.open_source_removing_unavailable)
                        } else {
                            stringResource(
                                R.string.open_source_remove_unavailable_button,
                                state.unavailableUnpinnedCount,
                            )
                        },
                        modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            if (state.isChecking && state.checkTotal > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    LinearProgressIndicator(
                        progress = { state.checkCompleted.toFloat() / state.checkTotal.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = state.checkProgressText ?: "${state.checkCompleted}/${state.checkTotal} · Checking tunnels",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!state.xrayCoreAvailable) {
                Text(
                    text = "Xray runtime core is not installed. Download it in settings.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.vpnState.activeTransport?.name == "XRAY" &&
                state.vpnState.sessionOwner == VpnSessionOwner.OPEN_SOURCE
            ) {
                Text(
                    text = "Status: ${state.vpnState.status.name.lowercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.vpnState.status == VpnConnectionStatus.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun CheckSelectedButton(
    state: OpenSourceUiState,
    onCheckSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = state.selectedProfile?.lastTestStatus ?: ProxyTestStatus.NOT_TESTED
    val contentColor = when (status) {
        ProxyTestStatus.NOT_TESTED -> MaterialTheme.colorScheme.primary
        ProxyTestStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ProxyTestStatus.AVAILABLE -> MaterialTheme.colorScheme.secondary
        ProxyTestStatus.UNAVAILABLE,
        ProxyTestStatus.UNSUPPORTED,
        -> MaterialTheme.colorScheme.error
    }
    val enabled = state.selectedProfile != null &&
        !state.isChecking &&
        !state.isRemovingUnavailable &&
        state.xrayCoreAvailable

    Button(
        onClick = onCheckSelected,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = contentColor.copy(alpha = 0.16f),
            contentColor = contentColor,
            disabledContainerColor = contentColor.copy(alpha = 0.08f),
            disabledContentColor = contentColor.copy(alpha = 0.55f),
        ),
        border = BorderStroke(
            1.dp,
            contentColor.copy(alpha = if (enabled) 0.42f else 0.18f),
        ),
        modifier = modifier.heightIn(min = 50.dp),
        contentPadding = ButtonDefaults.ContentPadding,
        shape = MaterialTheme.shapes.medium,
    ) {
        when (status) {
            ProxyTestStatus.RUNNING -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = contentColor,
            )
            ProxyTestStatus.NOT_TESTED,
            ProxyTestStatus.AVAILABLE,
            -> Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            ProxyTestStatus.UNAVAILABLE,
            ProxyTestStatus.UNSUPPORTED,
            -> Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(
            text = when (status) {
                ProxyTestStatus.NOT_TESTED -> "Check selected route"
                ProxyTestStatus.RUNNING -> "Checking selected route"
                ProxyTestStatus.AVAILABLE -> "Selected route is available"
                ProxyTestStatus.UNAVAILABLE -> "Selected route is unavailable"
                ProxyTestStatus.UNSUPPORTED -> "Selected route is unsupported"
            },
            modifier = Modifier.padding(start = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProxyProfileCard(
    profile: ProxyProfileSummary,
    checked: Boolean,
    selectionMode: Boolean,
    hostPingMs: Long?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPinChange: (Boolean) -> Unit,
) {
    var menuExpanded by remember(profile.id) { mutableStateOf(false) }
    val rowTint = when {
        checked -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)
        profile.isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    val hasStatusLine = profile.lastTestStatus != ProxyTestStatus.NOT_TESTED ||
        profile.isStale ||
        hostPingMs != null

    InsetGroup(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowTint)
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (profile.isSelected || checked) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = if (checked) "Selected for action" else "Active",
                        tint = if (checked) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = profile.name,
                    modifier = Modifier
                        .padding(start = if (profile.isSelected || checked) 8.dp else 0.dp)
                        .weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = { onPinChange(!profile.isPinned) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = if (profile.isPinned) "Unpin" else "Pin",
                        tint = if (profile.isPinned) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (!selectionMode) {
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Configuration actions")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onCopy()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onEdit()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
            Text(
                "${profile.protocol.scheme} · ${profile.host}:${profile.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${profile.transport.name} · ${profile.security.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasStatusLine) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (profile.lastTestStatus) {
                        ProxyTestStatus.AVAILABLE -> StatusCapsule(
                            profile.lastLatencyMs?.let { "${it} ms" } ?: "Available",
                            MaterialTheme.colorScheme.secondary,
                        )
                        ProxyTestStatus.UNAVAILABLE -> StatusCapsule(
                            "Unavailable",
                            MaterialTheme.colorScheme.error,
                        )
                        ProxyTestStatus.RUNNING -> StatusCapsule(
                            "Checking",
                            MaterialTheme.colorScheme.primary,
                        )
                        ProxyTestStatus.UNSUPPORTED -> StatusCapsule(
                            "Unsupported",
                            MaterialTheme.colorScheme.error,
                        )
                        ProxyTestStatus.NOT_TESTED -> Unit
                    }
                    if (profile.isStale) {
                        Text(
                            text = "Stale",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Box(modifier = Modifier.weight(1f))
                    hostPingMs?.let { latencyMs ->
                        Text(
                            text = "Host $latencyMs ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyProxyList(onAdd: () -> Unit) {
    EmptyState(
        icon = Icons.Default.Public,
        title = "No configurations",
        message = "Refresh public routes or add a configuration manually.",
        actionLabel = "Add configuration",
        onAction = onAdd,
    )
}

@Composable
private fun ProfileEditorDialog(
    editor: ProxyEditorState,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editor.profileId == null) "Add configuration" else "Edit configuration") },
        text = {
            OutlinedTextField(
                value = editor.rawUri,
                onValueChange = onValueChange,
                label = { Text("VLESS / VMess / Trojan URI") },
                placeholder = { Text("vless://uuid@server:443?security=reality&type=xhttp#Name") },
                minLines = 5,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BulkImportDialog(
    initialText: String,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import configurations") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("One URI per line") },
                minLines = 8,
                maxLines = 16,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun sensitiveClipData(label: String, value: String): ClipData {
    return ClipData.newPlainText(label, value).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
    }
}

private const val GITHUB_REPOSITORY_URL =
    "https://github.com/stansful/ssh-vpn-client-kotlin/tree/master"
private const val SCROLL_JUMP_THRESHOLD = 8
