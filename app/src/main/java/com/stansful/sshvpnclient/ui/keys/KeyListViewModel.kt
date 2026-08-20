package com.stansful.sshvpnclient.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.KeyInUseException
import com.stansful.sshvpnclient.domain.model.SshPrivateKeySummary
import com.stansful.sshvpnclient.domain.usecase.key.DeleteSshPrivateKeyUseCase
import com.stansful.sshvpnclient.domain.usecase.key.GetSshPrivateKeyListUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KeyListItem(
    val key: SshPrivateKeySummary,
    val usageCount: Int,
)

data class KeyListUiState(
    val items: List<KeyListItem> = emptyList(),
    val message: String? = null,
)

class KeyListViewModel(
    getSshPrivateKeyListUseCase: GetSshPrivateKeyListUseCase,
    private val deleteSshPrivateKeyUseCase: DeleteSshPrivateKeyUseCase,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)

    val uiState = combine(
        getSshPrivateKeyListUseCase().map { keys ->
            keys.map { key ->
                KeyListItem(
                    key = key,
                    usageCount = key.usageCount,
                )
            }
        },
        message,
    ) { items, currentMessage ->
        KeyListUiState(items = items, message = currentMessage)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = KeyListUiState(),
    )

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                deleteSshPrivateKeyUseCase(id)
                message.value = "SSH key deleted"
            } catch (error: KeyInUseException) {
                message.value = error.message
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                message.value = error.message ?: "Unable to delete SSH key"
            }
        }
    }

    fun clearMessage() {
        message.update { null }
    }
}
