package com.stansful.sshvpnclient.ui.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.ui.common.AppScreen
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import com.stansful.sshvpnclient.ui.common.ErrorMessage
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
        if (state.items.isEmpty()) {
            EmptyKeyList(onAdd)
        } else {
            ErrorMessage(state.message)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    Text("Delete")
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("No SSH keys yet", style = MaterialTheme.typography.titleMedium)
        FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("Add SSH key", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun KeyListCard(
    item: KeyListItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.key.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
            Text("Used by: ${item.usageCount} configs")
            item.key.note?.let { Text("Note: $it") }
            Text("Updated: ${formatDateTime(item.key.updatedAt)}")
        }
    }
}
