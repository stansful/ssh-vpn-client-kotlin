package com.stansful.sshvpnclient.ui.keyedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.SshPrivateKey
import com.stansful.sshvpnclient.domain.model.ValidationException
import com.stansful.sshvpnclient.domain.usecase.config.GetSshConfigListUseCase
import com.stansful.sshvpnclient.domain.usecase.key.AddSshPrivateKeyUseCase
import com.stansful.sshvpnclient.domain.usecase.key.GetSshPrivateKeyByIdUseCase
import com.stansful.sshvpnclient.domain.usecase.key.UpdateSshPrivateKeyUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class EditKeyForm(
    val id: String? = null,
    val name: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val note: String = "",
    val createdAt: Long? = null,
)

/**
 * [usedBy] = names of the servers that sign in with the edited key. [validationRound] counts failed
 * saves (the screen shakes the invalid fields on each); [savedKeyId] is set once the key is stored.
 */
data class EditKeyUiState(
    val form: EditKeyForm = EditKeyForm(),
    val errors: Map<String, String> = emptyMap(),
    val message: String? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val isEditing: Boolean = false,
    val usedBy: List<String> = emptyList(),
    val validationRound: Int = 0,
    val savedKeyId: String? = null,
)

class EditKeyViewModel(
    private val keyId: String?,
    private val addSshPrivateKeyUseCase: AddSshPrivateKeyUseCase,
    private val updateSshPrivateKeyUseCase: UpdateSshPrivateKeyUseCase,
    private val getSshPrivateKeyByIdUseCase: GetSshPrivateKeyByIdUseCase,
    getSshConfigListUseCase: GetSshConfigListUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EditKeyUiState(isEditing = keyId != null))
    val uiState = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = keyId?.let { getSshPrivateKeyByIdUseCase(it) } ?: return@launch
            mutableState.update {
                it.copy(
                    form = existing.toForm(),
                    isEditing = true,
                )
            }
        }
        if (keyId != null) {
            viewModelScope.launch {
                getSshConfigListUseCase().collect { configs ->
                    val names = configs.filter { it.privateKeyId == keyId }.map { it.name }
                    mutableState.update { it.copy(usedBy = names) }
                }
            }
        }
    }

    fun updateForm(transform: (EditKeyForm) -> EditKeyForm) {
        mutableState.update { it.copy(form = transform(it.form), errors = emptyMap(), message = null) }
    }

    fun save() {
        val state = mutableState.value
        if (state.isSaving || state.isSaved) return
        val form = state.form.copy(id = state.form.id ?: UUID.randomUUID().toString())
        mutableState.update { it.copy(form = form, isSaving = true, message = null) }
        viewModelScope.launch {
            val key = form.toDomain(System.currentTimeMillis())
            try {
                if (state.isEditing) {
                    updateSshPrivateKeyUseCase(key)
                } else {
                    addSshPrivateKeyUseCase(key)
                }
                mutableState.update {
                    it.copy(isSaving = false, isSaved = true, savedKeyId = key.id, errors = emptyMap(), message = null)
                }
            } catch (error: ValidationException) {
                mutableState.update {
                    it.copy(
                        isSaving = false,
                        validationRound = it.validationRound + 1,
                        errors = error.errors.associate { item -> item.field to item.message },
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                mutableState.update { it.copy(isSaving = false, message = error.message ?: "Unable to save SSH key") }
            }
        }
    }

    private fun SshPrivateKey.toForm(): EditKeyForm {
        return EditKeyForm(
            id = id,
            name = name,
            privateKey = privateKey,
            passphrase = passphrase.orEmpty(),
            note = note.orEmpty(),
            createdAt = createdAt,
        )
    }

    private fun EditKeyForm.toDomain(now: Long): SshPrivateKey {
        return SshPrivateKey(
            id = id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            privateKey = privateKey.trim(),
            passphrase = passphrase.takeIf { it.isNotBlank() },
            note = note.trim().ifBlank { null },
            createdAt = createdAt ?: now,
            updatedAt = now,
        )
    }
}
