package com.stansful.sshvpnclient.ui.configedit

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.ui.common.AppScreen
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import com.stansful.sshvpnclient.ui.common.ErrorMessage
import com.stansful.sshvpnclient.ui.common.FormField
import com.stansful.sshvpnclient.ui.common.InsetGroup
import com.stansful.sshvpnclient.ui.common.PrimaryActionButton
import com.stansful.sshvpnclient.ui.common.SectionHeader
import com.stansful.sshvpnclient.ui.common.SecretFormField
import com.stansful.sshvpnclient.ui.common.SecondaryActionButton
import com.stansful.sshvpnclient.ui.common.VerticalGap

@Composable
fun EditConfigRoute(
    container: AppContainer,
    configId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onAddKey: () -> Unit,
) {
    val viewModel: EditConfigViewModel = viewModel(
        key = "edit-config-${configId ?: "new"}",
        factory = AppViewModelFactory(container, configId = configId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onSaved()
    }

    EditConfigScreen(
        state = state,
        onBack = onBack,
        onAddKey = onAddKey,
        onSave = viewModel::save,
        onFormChange = viewModel::updateForm,
        onAuthTypeSelected = viewModel::selectAuthType,
        onPrivateKeySelected = viewModel::selectPrivateKey,
    )
}

@Composable
private fun EditConfigScreen(
    state: EditConfigUiState,
    onBack: () -> Unit,
    onAddKey: () -> Unit,
    onSave: () -> Unit,
    onFormChange: ((EditConfigForm) -> EditConfigForm) -> Unit,
    onAuthTypeSelected: (AuthType) -> Unit,
    onPrivateKeySelected: (String) -> Unit,
) {
    AppScreen(
        title = if (state.isEditing) "Edit configuration" else "Add configuration",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Profile")
                InsetGroup {
                    FormField(
                        value = state.form.name,
                        onValueChange = { value -> onFormChange { it.copy(name = value) } },
                        label = "Name",
                        placeholder = "e.g. Netherlands SSH",
                        error = state.errors["name"],
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Server")
                InsetGroup {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FormField(
                            value = state.form.host,
                            onValueChange = { value -> onFormChange { it.copy(host = value) } },
                            label = "Host",
                            placeholder = "vpn.example.com or 203.0.113.10",
                            error = state.errors["host"],
                        )
                        FormField(
                            value = state.form.port,
                            onValueChange = { value -> onFormChange { it.copy(port = value) } },
                            label = "Port",
                            placeholder = "22",
                            error = state.errors["port"],
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        FormField(
                            value = state.form.username,
                            onValueChange = { value -> onFormChange { it.copy(username = value) } },
                            label = "Username",
                            placeholder = "e.g. root",
                            error = state.errors["username"],
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Authentication")
                InsetGroup {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AuthTypeSelector(
                            selected = state.form.authType,
                            onSelected = onAuthTypeSelected,
                        )
                        if (state.form.authType == AuthType.PASSWORD) {
                            SecretFormField(
                                value = state.form.password,
                                onValueChange = { value -> onFormChange { it.copy(password = value) } },
                                label = "Password",
                                placeholder = "SSH account password",
                                error = state.errors["password"],
                            )
                        } else {
                            PrivateKeySelector(
                                state = state,
                                onAddKey = onAddKey,
                                onPrivateKeySelected = onPrivateKeySelected,
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Security")
                InsetGroup {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormField(
                            value = state.form.fingerprint,
                            onValueChange = { value -> onFormChange { it.copy(fingerprint = value) } },
                            label = "Host fingerprint",
                            placeholder = "SHA256:atzfmdcrqQzoXZfKHLarePDyMw/G5NYfJ3h1eHUVS9g",
                        )
                        if (state.form.fingerprint.isBlank()) {
                            Text(
                                text = "A trusted fingerprint is strongly recommended to verify the server before authentication.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Advanced")
                InsetGroup {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FormField(
                            value = state.form.keepAliveIntervalSec,
                            onValueChange = { value -> onFormChange { it.copy(keepAliveIntervalSec = value) } },
                            label = "KeepAlive interval, sec",
                            placeholder = "30",
                            error = state.errors["keepAliveIntervalSec"],
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        FormField(
                            value = state.form.note,
                            onValueChange = { value -> onFormChange { it.copy(note = value) } },
                            label = "Note",
                            placeholder = "Optional connection description",
                            singleLine = false,
                            minLines = 3,
                        )
                    }
                }
            }

            ErrorMessage(state.message)
            PrimaryActionButton(text = "Save configuration", onClick = onSave)
            VerticalGap()
        }
    }
}

@Composable
private fun AuthTypeSelector(
    selected: AuthType,
    onSelected: (AuthType) -> Unit,
) {
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AuthType.entries.forEach { authType ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = selected == authType,
                        role = Role.RadioButton,
                        onClick = { onSelected(authType) },
                    ),
            ) {
                RadioButton(
                    selected = selected == authType,
                    onClick = null,
                )
                Text(authType.label)
            }
        }
    }
}

@Composable
private fun PrivateKeySelector(
    state: EditConfigUiState,
    onAddKey: () -> Unit,
    onPrivateKeySelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedKey = state.keys.firstOrNull { it.id == state.form.privateKeyId }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Private key", style = MaterialTheme.typography.labelLarge)
        if (state.keys.isEmpty()) {
            Text("No saved SSH keys")
        } else {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(selectedKey?.name ?: "Select existing key")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    state.keys.forEach { key ->
                        DropdownMenuItem(
                            text = { Text(key.name) },
                            onClick = {
                                onPrivateKeySelected(key.id)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
        ErrorMessage(state.errors["privateKeyId"])
        SecondaryActionButton(text = "Add new key", onClick = onAddKey)
    }
}
