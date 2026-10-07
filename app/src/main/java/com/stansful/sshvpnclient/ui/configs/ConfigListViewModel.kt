package com.stansful.sshvpnclient.ui.configs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.SshConfigSummary
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.usecase.config.DeleteSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.config.SelectSshConfigUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConfigListItem(
    val config: SshConfigSummary,
    val keyName: String?,
    val isSelected: Boolean,
)

/**
 * [removedNames] = names of servers that left the list while this screen was alive (by id), so a
 * session still running through a deleted server can be named.
 */
data class ConfigListUiState(
    val items: List<ConfigListItem> = emptyList(),
    val isLoaded: Boolean = false,
    val removedNames: Map<String, String> = emptyMap(),
    val pendingDeleteId: String? = null,
    val isDeleting: Boolean = false,
    val deleteFailed: Boolean = false,
)

sealed interface ConfigListEvent {
    data class Deleted(val name: String) : ConfigListEvent

    /** Server [id] is now the selected one (stored). */
    data class Selected(val id: String) : ConfigListEvent

    /** Storing the selection failed; the list keeps the previous choice. */
    data class SelectFailed(val id: String) : ConfigListEvent
}

class ConfigListViewModel(
    configRepository: SshConfigRepository,
    private val selectSshConfigUseCase: SelectSshConfigUseCase,
    private val deleteSshConfigUseCase: DeleteSshConfigUseCase,
) : ViewModel() {
    private val deletion = MutableStateFlow(DeletionState())
    private val eventChannel = Channel<ConfigListEvent>(Channel.BUFFERED)

    // Every name seen since the screen opened; only touched by the sequential combine below.
    private val seenNames = mutableMapOf<String, String>()

    /** One-shot results for toasts and the shake of a card whose selection failed. */
    val events: Flow<ConfigListEvent> = eventChannel.receiveAsFlow()

    val uiState = combine(
        configRepository.observeSummaries(),
        deletion,
    ) { configs, currentDeletion ->
        configs.forEach { seenNames[it.id] = it.name }
        val currentIds = configs.mapTo(HashSet()) { it.id }
        ConfigListUiState(
            items = configs.map { config ->
                ConfigListItem(
                    config = config,
                    keyName = config.keyName,
                    isSelected = config.isSelected,
                )
            },
            isLoaded = true,
            removedNames = seenNames.filterKeys { it !in currentIds },
            pendingDeleteId = currentDeletion.pendingId,
            isDeleting = currentDeletion.isDeleting,
            deleteFailed = currentDeletion.failed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ConfigListUiState(),
    )

    fun select(id: String) {
        viewModelScope.launch {
            try {
                selectSshConfigUseCase(id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                eventChannel.send(ConfigListEvent.SelectFailed(id))
                return@launch
            }
            eventChannel.send(ConfigListEvent.Selected(id))
        }
    }

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
        val name = uiState.value.items.firstOrNull { it.config.id == id }?.config?.name.orEmpty()
        deletion.value = current.copy(isDeleting = true, failed = false)
        viewModelScope.launch {
            try {
                deleteSshConfigUseCase(id)
                deletion.value = DeletionState()
                eventChannel.send(ConfigListEvent.Deleted(name))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                deletion.update { it.copy(isDeleting = false, failed = true) }
            }
        }
    }

    private data class DeletionState(
        val pendingId: String? = null,
        val isDeleting: Boolean = false,
        val failed: Boolean = false,
    )
}
