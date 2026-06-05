package com.stansful.sshvpnclient.ui.configedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.ui.common.AppScreen
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import com.stansful.sshvpnclient.ui.common.ErrorMessage
import com.stansful.sshvpnclient.ui.common.FormField
import com.stansful.sshvpnclient.ui.common.PrimaryActionButton
import com.stansful.sshvpnclient.ui.common.SecondaryActionButton
import com.stansful.sshvpnclient.ui.common.SwitchRow
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
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onSaved()
    }

    EditConfigScreen(
        state = state,
        onBack = onBack,
        onAddKey = onAddKey,
        onSave = viewModel::save,
        onFormChange = viewModel::updateForm,
    )
}

@Composable
private fun EditConfigScreen(
    state: EditConfigUiState,
    onBack: () -> Unit,
    onAddKey: () -> Unit,
    onSave: () -> Unit,
    onFormChange: ((EditConfigForm) -> EditConfigForm) -> Unit,
) {
    AppScreen(
        title = if (state.isEditing) "Edit configuration" else "Add configuration",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FormField(
                value = state.form.name,
                onValueChange = { value -> onFormChange { it.copy(name = value) } },
                label = "Name",
                error = state.errors["name"],
            )
            FormField(
                value = state.form.host,
                onValueChange = { value -> onFormChange { it.copy(host = value) } },
                label = "Host",
                error = state.errors["host"],
            )
            FormField(
                value = state.form.port,
                onValueChange = { value -> onFormChange { it.copy(port = value) } },
                label = "Port",
                error = state.errors["port"],
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            FormField(
                value = state.form.username,
                onValueChange = { value -> onFormChange { it.copy(username = value) } },
                label = "Username",
                error = state.errors["username"],
            )

            AuthTypeSelector(
                selected = state.form.authType,
                onSelected = { authType ->
                    onFormChange { form ->
                        form.copy(
                            authType = authType,
                            privateKeyId = if (
                                authType == AuthType.PRIVATE_KEY &&
                                form.privateKeyId.isBlank()
                            ) {
                                state.keys.firstOrNull()?.id.orEmpty()
                            } else {
                                form.privateKeyId
                            },
                        )
                    }
                },
            )

            if (state.form.authType == AuthType.PASSWORD) {
                FormField(
                    value = state.form.password,
                    onValueChange = { value -> onFormChange { it.copy(password = value) } },
                    label = "Password",
                    error = state.errors["password"],
                    visualTransformation = PasswordVisualTransformation(),
                )
            } else {
                PrivateKeySelector(
                    state = state,
                    onAddKey = onAddKey,
                    onFormChange = onFormChange,
                )
            }

            FormField(
                value = state.form.fingerprint,
                onValueChange = { value -> onFormChange { it.copy(fingerprint = value) } },
                label = "Fingerprint",
            )
            FormField(
                value = state.form.keepAliveIntervalSec,
                onValueChange = { value -> onFormChange { it.copy(keepAliveIntervalSec = value) } },
                label = "KeepAlive interval, sec",
                error = state.errors["keepAliveIntervalSec"],
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            SwitchRow(
                title = "UDP forwarding",
                subtitle = "Experimental flag for future forwarding support",
                checked = state.form.enableUdpForwarding,
                onCheckedChange = { checked ->
                    onFormChange { it.copy(enableUdpForwarding = checked) }
                },
            )
            FormField(
                value = state.form.note,
                onValueChange = { value -> onFormChange { it.copy(note = value) } },
                label = "Note",
                singleLine = false,
                minLines = 3,
            )

            ErrorMessage(state.message)
            PrimaryActionButton(text = "Save", onClick = onSave)
            VerticalGap()
        }
    }
}

@Composable
private fun AuthTypeSelector(
    selected: AuthType,
    onSelected: (AuthType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Auth type", style = MaterialTheme.typography.labelLarge)
        AuthType.entries.forEach { authType ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(
                    selected = selected == authType,
                    onClick = { onSelected(authType) },
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
    onFormChange: ((EditConfigForm) -> EditConfigForm) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedKey = state.keys.firstOrNull { it.id == state.form.privateKeyId }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Private key", style = MaterialTheme.typography.labelLarge)
        if (state.keys.isEmpty()) {
            Text("No saved SSH keys")
        } else {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(selectedKey?.name ?: "Select existing key")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                state.keys.forEach { key ->
                    DropdownMenuItem(
                        text = { Text(key.name) },
                        onClick = {
                            onFormChange { it.copy(privateKeyId = key.id) }
                            expanded = false
                        },
                    )
                }
            }
        }
        ErrorMessage(state.errors["privateKeyId"])
        SecondaryActionButton(text = "Add new key", onClick = onAddKey)
    }
}
