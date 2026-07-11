package com.stansful.sshvpnclient.ui.configedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.SshPrivateKeySummary
import com.stansful.sshvpnclient.domain.model.ValidationException
import com.stansful.sshvpnclient.domain.usecase.config.AddSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.config.GetSshConfigByIdUseCase
import com.stansful.sshvpnclient.domain.usecase.config.UpdateSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.key.GetSshPrivateKeyListUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class EditConfigForm(
    val id: String? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",
    val privateKeyId: String = "",
    val fingerprint: String = "",
    val keepAliveIntervalSec: String = "30",
    val enableUdpForwarding: Boolean = false,
    val note: String = "",
    val createdAt: Long? = null,
)

data class EditConfigUiState(
    val form: EditConfigForm = EditConfigForm(),
    val keys: List<SshPrivateKeySummary> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val message: String? = null,
    val isSaved: Boolean = false,
    val isEditing: Boolean = false,
)

class EditConfigViewModel(
    private val configId: String?,
    private val addSshConfigUseCase: AddSshConfigUseCase,
    private val updateSshConfigUseCase: UpdateSshConfigUseCase,
    private val getSshConfigByIdUseCase: GetSshConfigByIdUseCase,
    getSshPrivateKeyListUseCase: GetSshPrivateKeyListUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EditConfigUiState(isEditing = configId != null))
    val uiState = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            getSshPrivateKeyListUseCase().collect { keys ->
                mutableState.update { state ->
                    state.copy(
                        keys = keys,
                        form = state.form.withDefaultPrivateKey(keys),
                        errors = state.errors.clearPrivateKeyErrorIfSelected(
                            state.form.withDefaultPrivateKey(keys),
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            val existing = configId?.let { getSshConfigByIdUseCase(it) } ?: return@launch
            mutableState.update {
                it.copy(
                    form = existing.toForm(),
                    isEditing = true,
                )
            }
        }
    }

    fun updateForm(transform: (EditConfigForm) -> EditConfigForm) {
        mutableState.update { it.copy(form = transform(it.form), errors = emptyMap(), message = null) }
    }

    fun selectAuthType(authType: AuthType) {
        mutableState.update { state ->
            val form = state.form.copy(authType = authType).withDefaultPrivateKey(state.keys)
            state.copy(form = form, errors = emptyMap(), message = null)
        }
    }

    fun selectPrivateKey(keyId: String) {
        mutableState.update { state ->
            state.copy(
                form = state.form.copy(privateKeyId = keyId),
                errors = state.errors - "privateKeyId",
                message = null,
            )
        }
    }

    fun save() {
        viewModelScope.launch {
            val state = mutableState.value
            val now = System.currentTimeMillis()
            val form = state.form.withDefaultPrivateKey(state.keys)
            if (form != state.form) {
                mutableState.update { it.copy(form = form) }
            }
            val config = form.toDomain(now)

            try {
                if (state.isEditing) {
                    updateSshConfigUseCase(config)
                } else {
                    addSshConfigUseCase(config)
                }
                mutableState.update { it.copy(isSaved = true, errors = emptyMap(), message = null) }
            } catch (error: ValidationException) {
                mutableState.update {
                    it.copy(errors = error.errors.associate { item -> item.field to item.message })
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(message = error.message ?: "Unable to save configuration") }
            }
        }
    }

    private fun SshConfig.toForm(): EditConfigForm {
        return EditConfigForm(
            id = id,
            name = name,
            host = host,
            port = port.toString(),
            username = username,
            authType = authType,
            password = password.orEmpty(),
            privateKeyId = privateKeyId.orEmpty(),
            fingerprint = fingerprint.orEmpty(),
            keepAliveIntervalSec = keepAliveIntervalSec.toString(),
            // DNS UDP/53 is always handled; general UDP is not representable by SSH direct-tcpip.
            enableUdpForwarding = false,
            note = note.orEmpty(),
            createdAt = createdAt,
        )
    }

    private fun EditConfigForm.toDomain(now: Long): SshConfig {
        val parsedPort = port.toIntOrNull() ?: 0
        val parsedKeepAlive = keepAliveIntervalSec.toIntOrNull() ?: 0
        val configId = id ?: UUID.randomUUID().toString()

        return SshConfig(
            id = configId,
            name = name.trim(),
            host = host.trim(),
            port = parsedPort,
            username = username.trim(),
            authType = authType,
            password = password.takeIf { authType == AuthType.PASSWORD },
            privateKeyId = privateKeyId.takeIf { authType == AuthType.PRIVATE_KEY },
            fingerprint = fingerprint.trim().ifBlank { null },
            keepAliveIntervalSec = parsedKeepAlive,
            enableUdpForwarding = enableUdpForwarding,
            note = note.trim().ifBlank { null },
            createdAt = createdAt ?: now,
            updatedAt = now,
        )
    }

    private fun EditConfigForm.withDefaultPrivateKey(keys: List<SshPrivateKeySummary>): EditConfigForm {
        if (authType != AuthType.PRIVATE_KEY || privateKeyId.isNotBlank()) return this
        return copy(privateKeyId = keys.firstOrNull()?.id.orEmpty())
    }

    private fun Map<String, String>.clearPrivateKeyErrorIfSelected(
        form: EditConfigForm,
    ): Map<String, String> {
        if (form.privateKeyId.isBlank()) return this
        return this - "privateKeyId"
    }
}
