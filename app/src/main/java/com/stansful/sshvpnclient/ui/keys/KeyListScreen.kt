package com.stansful.sshvpnclient.ui.keys

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
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
import com.stansful.sshvpnclient.ui.common.AppScreen
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import com.stansful.sshvpnclient.ui.common.EmptyState
import com.stansful.sshvpnclient.ui.common.ErrorMessage
import com.stansful.sshvpnclient.ui.common.InsetGroup
import com.stansful.sshvpnclient.ui.common.InsetRow
import com.stansful.sshvpnclient.ui.common.formatDateTime

@Composable
fun KeyListRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val viewModel: KeyListViewModel = viewModel(factory = AppViewModelFactory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    KeyListScreen(
        state = state,
        onBack = onBack,
        onAdd = onAdd,
        onEdit = onEdit,
        onDelete = viewModel::delete,
    )
}

@Composable
private fun KeyListScreen(
    state: KeyListUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<KeyListItem?>(null) }

    AppScreen(
        title = "SSH Keys",
        onBack = onBack,
        actions = {
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add SSH key")
            }
        },
    ) {
        ErrorMessage(state.message)
        if (state.items.isEmpty()) {
            EmptyKeyList(onAdd)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.items, key = { it.key.id }) { item ->
                    KeyListCard(
                        item = item,
                        onEdit = { onEdit(item.key.id) },
                        onDelete = { pendingDelete = item },
                    )
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete SSH key") },
            text = { Text("Delete ${item.key.name}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(item.key.id)
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
private fun EmptyKeyList(onAdd: () -> Unit) {
    EmptyState(
        icon = Icons.Default.Key,
        title = "No SSH keys",
        message = "Store a private key securely for key-based authentication.",
        actionLabel = "Add SSH key",
        onAction = onAdd,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
    )
}

@Composable
private fun KeyListCard(
    item: KeyListItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val details = buildString {
        append("Used by ${item.usageCount} configurations")
        append(" • Updated ${formatDateTime(item.key.updatedAt)}")
        item.key.note?.takeIf { it.isNotBlank() }?.let { note -> append("\n$note") }
    }

    InsetGroup(contentPadding = PaddingValues(0.dp)) {
        InsetRow(
            title = item.key.name,
            subtitle = details,
            icon = Icons.Default.Key,
            onClick = onEdit,
            trailing = {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "SSH key actions")
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
