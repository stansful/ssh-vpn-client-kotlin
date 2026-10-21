package com.stansful.sshvpnclient.ui.configedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.SshHostKeyChange
import com.stansful.sshvpnclient.domain.model.SshPrivateKeySummary
import com.stansful.sshvpnclient.domain.model.ValidationException
import com.stansful.sshvpnclient.domain.repository.SshHostKeyChangeRepository
import com.stansful.sshvpnclient.domain.usecase.config.AcceptSshHostKeyUseCase
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

/** One-shot outcome of "Accept new key", shown as a toast. */
enum class HostKeyAcceptResult { Accepted, Failed }

/**
 * [errors] appear after the first failed save ([attempted]); from then on every edit re-validates, so
 * fixed fields clear and [validationRound] counts the failed saves (the screen jumps to the first
 * error on each one). [hostKeyChange] is set while the last connection found a host key that differs
 * from the pinned one.
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
    val hostKeyChange: SshHostKeyChange? = null,
    val isAcceptingHostKey: Boolean = false,
    val hostKeyAcceptResult: HostKeyAcceptResult? = null,
)

class EditConfigViewModel(
    private val configId: String?,
    private val addSshConfigUseCase: AddSshConfigUseCase,
    private val updateSshConfigUseCase: UpdateSshConfigUseCase,
    private val getSshConfigByIdUseCase: GetSshConfigByIdUseCase,
    getSshPrivateKeyListUseCase: GetSshPrivateKeyListUseCase,
    private val keyTraitsReader: SshKeyTraitsReader,
    private val hostKeyChanges: SshHostKeyChangeRepository,
    private val acceptSshHostKeyUseCase: AcceptSshHostKeyUseCase,
) : ViewModel() {
    private val validator = SshConfigValidator()
    private val mutableState = MutableStateFlow(EditConfigUiState(isEditing = configId != null))
    val uiState = mutableState.asStateFlow()

    /** The fingerprint as last read from or written to storage, to tell a user edit from no edit. */
    private var storedFingerprint: String = ""

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
            storedFingerprint = existing.fingerprint.orEmpty()
            mutableState.update { state ->
                val form = existing.toForm()
                state.copy(
                    form = form,
                    isEditing = true,
                    errors = state.revalidated(form),
                )
            }
        }
        if (configId != null) {
            viewModelScope.launch {
                hostKeyChanges.observe(configId).collectLatest { change ->
                    mutableState.update { it.copy(hostKeyChange = change) }
                }
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

    /**
     * Pins the key the server presented instead of the saved one, right away (other edits still wait
     * for Save). The next connection checks the new key. Never runs together with [save]: both write the
     * fingerprint.
     */
    fun acceptNewHostKey() {
        val state = mutableState.value
        val change = state.hostKeyChange ?: return
        if (state.isAcceptingHostKey || state.isSaving || state.isSaved) return
        mutableState.update { it.copy(isAcceptingHostKey = true, hostKeyAcceptResult = null) }
        viewModelScope.launch {
            try {
                val saved = acceptSshHostKeyUseCase(change)
                if (saved) storedFingerprint = change.observedFingerprint
                mutableState.update {
                    it.copy(
                        form = if (saved) it.form.copy(fingerprint = change.observedFingerprint) else it.form,
                        isAcceptingHostKey = false,
                        hostKeyAcceptResult = if (saved) HostKeyAcceptResult.Accepted else HostKeyAcceptResult.Failed,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(isAcceptingHostKey = false, hostKeyAcceptResult = HostKeyAcceptResult.Failed)
                }
            }
        }
    }

    fun consumeHostKeyAcceptResult() {
        mutableState.update { it.copy(hostKeyAcceptResult = null) }
    }

    fun save() {
        val state = mutableState.value
        // An "Accept new key" in flight would race this save over the fingerprint; it takes a moment.
        if (state.isSaving || state.isSaved || state.isAcceptingHostKey) return
        // A new server gets its id once, so a retried save after a failure cannot create a second copy.
        val form = state.form.withDefaultPrivateKey(state.keys).let { draft ->
            draft.copy(id = draft.id ?: UUID.randomUUID().toString())
        }
        mutableState.update { it.copy(form = form, isSaving = true, message = null) }
        viewModelScope.launch {
            try {
                val fingerprintEdited = form.fingerprint.trim() != storedFingerprint.trim()
                val config = form.toDomain(System.currentTimeMillis())
                if (state.isEditing) {
                    // Not touched here: the write keeps what is stored at that moment, e.g. a key pinned
                    // on first use while the editor was open (reading it here first would leave a gap).
                    updateSshConfigUseCase(config, keepSavedFingerprint = !fingerprintEdited)
                } else {
                    addSshConfigUseCase(config)
                }
                if (fingerprintEdited) {
                    storedFingerprint = config.fingerprint.orEmpty()
                    // A fingerprint set by hand settles a pending "host key changed" either way.
                    hostKeyChanges.clear(config.id)
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
