package com.stansful.sshvpnclient.ui.configs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.ui.common.AppScreen
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import com.stansful.sshvpnclient.ui.common.EmptyState
import com.stansful.sshvpnclient.ui.common.ErrorMessage
import com.stansful.sshvpnclient.ui.common.InsetGroup
import com.stansful.sshvpnclient.ui.common.InsetRow

@Composable
fun ConfigListRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val viewModel: ConfigListViewModel = viewModel(factory = AppViewModelFactory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ConfigListScreen(
        state = state,
        onBack = onBack,
        onAdd = onAdd,
        onEdit = onEdit,
        onSelect = viewModel::select,
        onDelete = viewModel::delete,
    )
}

@Composable
private fun ConfigListScreen(
    state: ConfigListUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ConfigListItem?>(null) }

    AppScreen(
        title = "Configurations",
        onBack = onBack,
        actions = {
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add configuration")
            }
        },
    ) {
        ErrorMessage(state.message)
        if (state.items.isEmpty()) {
            EmptyConfigList(onAdd)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.items, key = { it.config.id }) { item ->
                    ConfigListCard(
                        item = item,
                        onSelect = { onSelect(item.config.id) },
                        onEdit = { onEdit(item.config.id) },
                        onDelete = { pendingDelete = item },
                    )
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete configuration") },
            text = { Text("Delete ${item.config.name}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(item.config.id)
                        pendingDelete = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun EmptyConfigList(onAdd: () -> Unit) {
    EmptyState(
        icon = Icons.Default.Terminal,
        title = "No SSH configurations",
        message = "Add a server to start a secure connection.",
        actionLabel = "Add configuration",
        onAction = onAdd,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
    )
}

@Composable
private fun ConfigListCard(
    item: ConfigListItem,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val config = item.config
    var menuExpanded by remember { mutableStateOf(false) }
    val details = buildString {
        append("${config.username}@${config.host}:${config.port}")
        append("\n${config.authType.label}")
        if (config.authType == AuthType.PRIVATE_KEY) {
            append(" • ${item.keyName ?: "Missing key"}")
        }
        config.note?.takeIf { it.isNotBlank() }?.let { note -> append("\n$note") }
    }

    InsetGroup(contentPadding = PaddingValues(0.dp)) {
        InsetRow(
            title = config.name,
            subtitle = details,
            onClick = onSelect,
            trailing = {
                if (item.isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Configuration actions")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
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
            },
        )
    }
}
