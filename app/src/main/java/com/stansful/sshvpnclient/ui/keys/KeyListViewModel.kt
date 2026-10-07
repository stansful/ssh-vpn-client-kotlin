package com.stansful.sshvpnclient.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.KeyInUseException
import com.stansful.sshvpnclient.domain.model.SshPrivateKeySummary
import com.stansful.sshvpnclient.domain.usecase.key.DeleteSshPrivateKeyUseCase
import com.stansful.sshvpnclient.domain.usecase.key.GetSshPrivateKeyListUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KeyListItem(
    val key: SshPrivateKeySummary,
    val usageCount: Int,
    val traits: SshKeyTraits? = null,
)

/** Why the last confirmed delete did not go through. */
sealed interface KeyDeleteError {
    /** A server still signs in with the key; [message] is the repository's explanation. */
    data class InUse(val message: String) : KeyDeleteError

    /** Storage failed; the dialog offers "Try again". */
    data object Storage : KeyDeleteError
}

data class KeyListUiState(
    val items: List<KeyListItem> = emptyList(),
    val isLoaded: Boolean = false,
    val pendingDeleteId: String? = null,
    val isDeleting: Boolean = false,
    val deleteError: KeyDeleteError? = null,
)

sealed interface KeyListEvent {
    data class Deleted(val name: String) : KeyListEvent
}

class KeyListViewModel(
    getSshPrivateKeyListUseCase: GetSshPrivateKeyListUseCase,
    private val deleteSshPrivateKeyUseCase: DeleteSshPrivateKeyUseCase,
    private val keyTraitsReader: SshKeyTraitsReader,
) : ViewModel() {
    private val deletion = MutableStateFlow(DeletionState())
    private val eventChannel = Channel<KeyListEvent>(Channel.BUFFERED)

    /** One-shot results for toasts. */
    val events: Flow<KeyListEvent> = eventChannel.receiveAsFlow()

    // Traits of the previous list, shown right away so known cards keep their tags while the rest load.
    private var knownTraits: Map<String, SshKeyTraits> = emptyMap()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val items: Flow<List<KeyListItem>> = getSshPrivateKeyListUseCase().transformLatest { keys ->
        fun withTraits(traits: Map<String, SshKeyTraits>) = keys.map { key ->
            KeyListItem(key = key, usageCount = key.usageCount, traits = traits[key.id])
        }
        emit(withTraits(knownTraits))
        knownTraits = keyTraitsReader.read(keys)
        emit(withTraits(knownTraits))
    }

    val uiState = combine(items, deletion) { currentItems, currentDeletion ->
        KeyListUiState(
            items = currentItems,
            isLoaded = true,
            pendingDeleteId = currentDeletion.pendingId,
            isDeleting = currentDeletion.isDeleting,
            deleteError = currentDeletion.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = KeyListUiState(),
    )

    fun askDelete(id: String) {
        deletion.value = DeletionState(pendingId = id)
    }

    fun cancelDelete() {
        if (deletion.value.isDeleting) return
        deletion.value = DeletionState()
    }

    fun confirmDelete() {
        val current = deletion.value
        val id = current.pendingId ?: return
        if (current.isDeleting) return
        val name = uiState.value.items.firstOrNull { it.key.id == id }?.key?.name.orEmpty()
        deletion.value = current.copy(isDeleting = true, error = null)
        viewModelScope.launch {
            try {
                deleteSshPrivateKeyUseCase(id)
                deletion.value = DeletionState()
                eventChannel.send(KeyListEvent.Deleted(name))
            } catch (error: KeyInUseException) {
                deletion.update { it.copy(isDeleting = false, error = KeyDeleteError.InUse(error.message.orEmpty())) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                deletion.update { it.copy(isDeleting = false, error = KeyDeleteError.Storage) }
            }
        }
    }

    private data class DeletionState(
        val pendingId: String? = null,
        val isDeleting: Boolean = false,
        val error: KeyDeleteError? = null,
    )
}
