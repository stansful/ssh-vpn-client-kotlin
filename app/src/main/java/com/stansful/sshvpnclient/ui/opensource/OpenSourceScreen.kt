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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.domain.model.OpenSourcePolicy
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.ui.common.AppScreen
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import kotlinx.coroutines.launch

@Composable
fun OpenSourceRoute(container: AppContainer) {
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
    onConnect: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    var pendingDeleteIds by remember { mutableStateOf<Set<String>>(emptySet()) }

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
            RiskBanner()
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
            ProtocolFilters(state.protocolFilter, viewModel::setProtocolFilter)
            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.profiles.isEmpty()) {
                EmptyProxyList(onRefresh = viewModel::synchronize, onAdd = { viewModel.openEditor() })
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
}

@Composable
private fun RiskBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = OpenSourcePolicy.DISCLAIMER,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.selectedProfile?.let { profile ->
            Text(
                text = "Selected: ${profile.name}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onConnect,
                enabled = state.selectedProfile != null && state.xrayCoreAvailable,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                Text(
                    when {
                        state.xrayConnected -> "Disconnect"
                        else -> "Connect"
                    },
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            FilledTonalButton(
                onClick = onRefresh,
                enabled = !state.isSyncing,
                modifier = Modifier.weight(1f),
            ) {
                if (state.isSyncing) CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                else Icon(Icons.Default.Refresh, contentDescription = null)
                Text("Refresh", modifier = Modifier.padding(start = 6.dp))
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
            ) { Text("Check selected") }
            FilledTonalButton(
                onClick = if (state.isChecking) onCancelChecks else onCheckAll,
                enabled = state.allProfileIds.isNotEmpty() && state.xrayCoreAvailable,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    if (state.isChecking) Icons.Default.Cancel else Icons.Default.Check,
                    contentDescription = null,
                )
                Text(
                    if (state.isChecking) "Cancel" else "Check all",
                    modifier = Modifier.padding(start = 6.dp),
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

@Composable
private fun ProtocolFilters(selected: ProxyProtocol?, onSelected: (ProxyProtocol?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelected(null) }, label = { Text("All") })
        ProxyProtocol.entries.forEach { protocol ->
            FilterChip(
                selected = selected == protocol,
                onClick = { onSelected(protocol) },
                label = { Text(protocol.name) },
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
private fun EmptyProxyList(onRefresh: () -> Unit, onAdd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("No public configurations yet")
        FilledTonalButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh configs") }
        FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add config") }
    }
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
