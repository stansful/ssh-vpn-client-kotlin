package com.stansful.sshvpnclient.ui.opensource

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.net.VpnService
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.R
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.ui.common.AppScreen
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import com.stansful.sshvpnclient.ui.main.CustomThemeColorsEditor
import com.stansful.sshvpnclient.ui.settings.SettingsSwitchRow
import com.stansful.sshvpnclient.ui.settings.ThemeModeSelector
import com.stansful.sshvpnclient.ui.settings.VpnModeSelector
import kotlinx.coroutines.launch

@Composable
fun OpenSourceRoute(
    container: AppContainer,
    openAppPicker: () -> Unit,
) {
    val viewModel: OpenSourceViewModel = viewModel(factory = AppViewModelFactory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.connect()
    }

    OpenSourceScreen(
        state = state,
        viewModel = viewModel,
        openAppPicker = openAppPicker,
        onConnect = {
            val permissionIntent = VpnService.prepare(context)
            if (permissionIntent == null) viewModel.connect() else vpnPermissionLauncher.launch(permissionIntent)
        },
    )
}

@Composable
private fun OpenSourceScreen(
    state: OpenSourceUiState,
    viewModel: OpenSourceViewModel,
    openAppPicker: () -> Unit,
    onConnect: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    var pendingDeleteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var settingsVisible by remember { mutableStateOf(false) }

    AppScreen(
        title = if (state.selectionMode) "Selected: ${state.selectedIds.size}" else "opensource",
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
                IconButton(onClick = { settingsVisible = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
                IconButton(onClick = viewModel::showBulkImport) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Import from clipboard")
                }
                IconButton(onClick = { viewModel.openEditor() }) {
                    Icon(Icons.Default.Add, contentDescription = "Add configuration")
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
                onRefresh = viewModel::synchronize,
                onCheckSelected = viewModel::checkSelected,
                onCheckAll = viewModel::checkAll,
                onCancelChecks = viewModel::cancelChecks,
                onConnect = if (state.xrayConnected) viewModel::disconnect else onConnect,
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text("Search configurations") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
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
                EmptyProxyList()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.profiles, key = ProxyProfileSummary::id) { profile ->
                        ProxyProfileCard(
                            profile = profile,
                            checked = profile.id in state.selectedIds,
                            selectionMode = state.selectionMode,
                            onClick = { viewModel.selectProfile(profile.id) },
                            onLongClick = { viewModel.beginBulkSelection(profile.id) },
                            onCopy = {
                                coroutineScope.launch {
                                    val rawUri = viewModel.rawUri(profile.id)
                                    clipboard.setClipEntry(ClipEntry(sensitiveClipData("Proxy configuration", rawUri)))
                                }
                            },
                            onEdit = { viewModel.openEditor(profile.id) },
                            onDelete = { pendingDeleteIds = setOf(profile.id) },
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
            onDismiss = { settingsVisible = false },
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
private fun RiskBanner(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.open_source_banner_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                IconButton(
                    onClick = { onExpandedChange(!expanded) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) {
                            stringResource(R.string.hide_diagnostics)
                        } else {
                            stringResource(R.string.show_diagnostics)
                        },
                        tint = if (expanded) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = stringResource(R.string.open_source_warning_message),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
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

    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .animateContentSize(),
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
                        text = stringResource(R.string.diagnostics_line_count, diagnostics.size),
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
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            SettingsSwitchRow(
                title = stringResource(R.string.debug_logs),
                checked = settings.showLogsOnOpenSource,
                onCheckedChange = onShowLogsChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.show_warning_dialog),
                checked = settings.showOpenSourceWarningOnEnter,
                onCheckedChange = onShowWarningDialogChange,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.vpn_mode), style = MaterialTheme.typography.labelLarge)
                VpnModeSelector(
                    selected = settings.vpnMode,
                    selectedAppsCount = settings.selectedAppPackages.size,
                    onSelected = onVpnModeChange,
                    onOpenAppPicker = onOpenAppPicker,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.theme), style = MaterialTheme.typography.labelLarge)
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

            Box(modifier = Modifier.padding(bottom = 12.dp))
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
    onConnect: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.selectedProfile?.let { profile ->
            Text(
                text = "Selected: ${profile.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                Text(
                    when {
                        state.xrayConnected -> "Disconnect"
                        state.sshActive -> "Switch to opensource"
                        else -> "Connect"
                    },
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            FilledTonalButton(
                onClick = onRefresh,
                enabled = !state.isSyncing,
                modifier = Modifier.weight(1f),
                contentPadding = ButtonDefaults.ContentPadding,
                shape = RoundedCornerShape(8.dp),
            ) {
                if (state.isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
                Text(
                    "Refresh",
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onCheckSelected,
                enabled = state.selectedProfile != null && !state.isChecking && state.xrayCoreAvailable,
                modifier = Modifier.weight(1f),
                contentPadding = ButtonDefaults.ContentPadding,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Check selected", style = MaterialTheme.typography.labelLarge)
            }
            FilledTonalButton(
                onClick = if (state.isChecking) onCancelChecks else onCheckAll,
                enabled = state.allProfileIds.isNotEmpty() && state.xrayCoreAvailable,
                modifier = Modifier.weight(1f),
                contentPadding = ButtonDefaults.ContentPadding,
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(
                    if (state.isChecking) Icons.Default.Cancel else Icons.Default.Check,
                    contentDescription = null,
                )
                Text(
                    if (state.isChecking) "Cancel" else "Check all",
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (state.isChecking && state.checkTotal > 0) {
            LinearProgressIndicator(
                progress = { state.checkCompleted.toFloat() / state.checkTotal.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${state.checkCompleted}/${state.checkTotal}", style = MaterialTheme.typography.bodySmall)
        }
        if (!state.xrayCoreAvailable) {
            Text(
                text = "Xray core is not packaged. Run scripts/build-xray-core.sh.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.vpnState.activeTransport?.name == "XRAY") {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProxyProfileCard(
    profile: ProxyProfileSummary,
    checked: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val borderColor = when {
        checked -> MaterialTheme.colorScheme.secondary
        profile.isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        border = BorderStroke(if (profile.isSelected || checked) 2.dp else 1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (profile.isSelected || checked) {
                    Icon(Icons.Default.Check, contentDescription = if (checked) "Selected for action" else "Active")
                }
                Text(
                    text = profile.name,
                    modifier = Modifier
                        .padding(start = if (profile.isSelected || checked) 8.dp else 0.dp)
                        .weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                if (!selectionMode) {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            }
            Text("${profile.host}:${profile.port}", style = MaterialTheme.typography.bodySmall)
            Text(
                "${profile.protocol.name} · ${profile.transport.name} · ${profile.security.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profile.isStale) StatusLabel("Stale", MaterialTheme.colorScheme.error)
                when (profile.lastTestStatus) {
                    ProxyTestStatus.AVAILABLE -> StatusLabel(
                        profile.lastLatencyMs?.let { "${it}ms" } ?: "Available",
                        MaterialTheme.colorScheme.secondary,
                    )
                    ProxyTestStatus.UNAVAILABLE -> StatusLabel("Unavailable", MaterialTheme.colorScheme.error)
                    ProxyTestStatus.RUNNING -> StatusLabel("Checking", MaterialTheme.colorScheme.primary)
                    ProxyTestStatus.UNSUPPORTED -> StatusLabel("Unsupported", MaterialTheme.colorScheme.error)
                    ProxyTestStatus.NOT_TESTED -> Unit
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(text: String, color: Color) {
    Text(text = text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun EmptyProxyList() {
    Text(
        text = "No public configurations yet",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
