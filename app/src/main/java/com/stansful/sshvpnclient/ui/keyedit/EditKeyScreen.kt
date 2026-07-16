package com.stansful.sshvpnclient.ui.keyedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.ui.common.AppScreen
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import com.stansful.sshvpnclient.ui.common.ErrorMessage
import com.stansful.sshvpnclient.ui.common.FormField
import com.stansful.sshvpnclient.ui.common.InsetGroup
import com.stansful.sshvpnclient.ui.common.PrimaryActionButton
import com.stansful.sshvpnclient.ui.common.SectionHeader
import com.stansful.sshvpnclient.ui.common.SecretFormField
import com.stansful.sshvpnclient.ui.common.VerticalGap

@Composable
fun EditKeyRoute(
    container: AppContainer,
    keyId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val viewModel: EditKeyViewModel = viewModel(
        key = "edit-key-${keyId ?: "new"}",
        factory = AppViewModelFactory(container, keyId = keyId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onSaved()
    }

    EditKeyScreen(
        state = state,
        onBack = onBack,
        onSave = viewModel::save,
        onFormChange = viewModel::updateForm,
    )
}

@Composable
private fun EditKeyScreen(
    state: EditKeyUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onFormChange: ((EditKeyForm) -> EditKeyForm) -> Unit,
) {
    AppScreen(
        title = if (state.isEditing) "Edit SSH key" else "Add SSH key",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Key details")
                InsetGroup {
                    FormField(
                        value = state.form.name,
                        onValueChange = { value -> onFormChange { it.copy(name = value) } },
                        label = "Key name",
                        placeholder = "e.g. production-ed25519",
                        error = state.errors["name"],
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(
                    title = "Private key",
                    subtitle = "The key stays on this device and is stored as sensitive data.",
                )
                InsetGroup {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecretFormField(
                            value = state.form.privateKey,
                            onValueChange = { value -> onFormChange { it.copy(privateKey = value) } },
                            label = "Private key",
                            placeholder = OPENSSH_PRIVATE_KEY_PLACEHOLDER,
                            error = state.errors["privateKey"],
                            singleLine = false,
                            minLines = 8,
                            copyLabel = "Private key",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        )
                        SecretFormField(
                            value = state.form.passphrase,
                            onValueChange = { value -> onFormChange { it.copy(passphrase = value) } },
                            label = "Passphrase",
                            placeholder = "Optional private key passphrase",
                            copyLabel = "Passphrase",
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Notes")
                InsetGroup {
                    FormField(
                        value = state.form.note,
                        onValueChange = { value -> onFormChange { it.copy(note = value) } },
                        label = "Note",
                        placeholder = "Optional key description",
                        singleLine = false,
                        minLines = 3,
                    )
                }
            }
            ErrorMessage(state.message)
            PrimaryActionButton(text = "Save key", onClick = onSave)
            VerticalGap()
        }
    }
}

private const val OPENSSH_PRIVATE_KEY_PLACEHOLDER = """-----BEGIN OPENSSH PRIVATE KEY-----
...
-----END OPENSSH PRIVATE KEY-----"""
