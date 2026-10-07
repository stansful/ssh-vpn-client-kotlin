package com.stansful.sshvpnclient.ui.configedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.SshPrivateKeySummary
import com.stansful.sshvpnclient.domain.model.ValidationException
import com.stansful.sshvpnclient.domain.usecase.config.AddSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.config.GetSshConfigByIdUseCase
import com.stansful.sshvpnclient.domain.usecase.config.SshConfigValidator
import com.stansful.sshvpnclient.domain.usecase.config.UpdateSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.key.GetSshPrivateKeyListUseCase
import com.stansful.sshvpnclient.ui.keys.SshKeyTraits
import com.stansful.sshvpnclient.ui.keys.SshKeyTraitsReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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

/**
 * [errors] appear after the first failed save ([attempted]); from then on every edit re-validates, so
 * fixed fields clear and [validationRound] counts the failed saves (the screen jumps to the first
 * error on each one).
 */
data class EditConfigUiState(
    val form: EditConfigForm = EditConfigForm(),
    val keys: List<SshPrivateKeySummary> = emptyList(),
    val keyTraits: Map<String, SshKeyTraits> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    val message: String? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val isEditing: Boolean = false,
    val attempted: Boolean = false,
    val validationRound: Int = 0,
)

class EditConfigViewModel(
    private val configId: String?,
    private val addSshConfigUseCase: AddSshConfigUseCase,
    private val updateSshConfigUseCase: UpdateSshConfigUseCase,
    private val getSshConfigByIdUseCase: GetSshConfigByIdUseCase,
    getSshPrivateKeyListUseCase: GetSshPrivateKeyListUseCase,
    private val keyTraitsReader: SshKeyTraitsReader,
) : ViewModel() {
    private val validator = SshConfigValidator()
    private val mutableState = MutableStateFlow(EditConfigUiState(isEditing = configId != null))
    val uiState = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            getSshPrivateKeyListUseCase().collectLatest { keys ->
                mutableState.update { state ->
                    val form = state.form.withDefaultPrivateKey(keys)
                    state.copy(
                        keys = keys,
                        form = form,
                        errors = state.errors.clearPrivateKeyErrorIfSelected(form),
                    )
                }
                val traits = keyTraitsReader.read(keys)
                mutableState.update { it.copy(keyTraits = traits) }
            }
        }
        viewModelScope.launch {
            val existing = configId?.let { getSshConfigByIdUseCase(it) } ?: return@launch
            mutableState.update { state ->
                val form = existing.toForm()
                state.copy(
                    form = form,
                    isEditing = true,
                    errors = state.revalidated(form),
                )
            }
        }
    }

    fun updateForm(transform: (EditConfigForm) -> EditConfigForm) {
        mutableState.update { state ->
            val form = transform(state.form)
            state.copy(form = form, errors = state.revalidated(form), message = null)
        }
    }

    fun selectAuthType(authType: AuthType) {
        mutableState.update { state ->
            val form = state.form.copy(authType = authType).withDefaultPrivateKey(state.keys)
            state.copy(form = form, errors = state.revalidated(form), message = null)
        }
    }

    fun selectPrivateKey(keyId: String) {
        mutableState.update { state ->
            val form = state.form.copy(privateKeyId = keyId)
            val errors = if (state.attempted) state.revalidated(form) else state.errors - "privateKeyId"
            state.copy(form = form, errors = errors, message = null)
        }
    }

    fun save() {
        val state = mutableState.value
        if (state.isSaving || state.isSaved) return
        // A new server gets its id once, so a retried save after a failure cannot create a second copy.
        val form = state.form.withDefaultPrivateKey(state.keys).let { draft ->
            draft.copy(id = draft.id ?: UUID.randomUUID().toString())
        }
        mutableState.update { it.copy(form = form, isSaving = true, message = null) }
        viewModelScope.launch {
            val config = form.toDomain(System.currentTimeMillis())
            try {
                if (state.isEditing) {
                    updateSshConfigUseCase(config)
                } else {
                    addSshConfigUseCase(config)
                }
                mutableState.update { it.copy(isSaving = false, isSaved = true, errors = emptyMap(), message = null) }
            } catch (error: ValidationException) {
                mutableState.update {
                    it.copy(
                        isSaving = false,
                        attempted = true,
                        validationRound = it.validationRound + 1,
                        errors = error.errors.associate { item -> item.field to item.message },
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(isSaving = false, message = error.message ?: "Unable to save configuration")
                }
            }
        }
    }

    private fun EditConfigUiState.revalidated(form: EditConfigForm): Map<String, String> {
        if (!attempted) return emptyMap()
        val candidate = form.withDefaultPrivateKey(keys).toDomain(now = 0L)
        return validator.validate(candidate).associate { it.field to it.message }
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
