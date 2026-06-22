package com.stansful.sshvpnclient.ui.configs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.SshConfigSummary
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.usecase.config.DeleteSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.config.SelectSshConfigUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConfigListItem(
    val config: SshConfigSummary,
    val keyName: String?,
    val isSelected: Boolean,
)

data class ConfigListUiState(
    val items: List<ConfigListItem> = emptyList(),
    val message: String? = null,
)

class ConfigListViewModel(
    configRepository: SshConfigRepository,
    private val selectSshConfigUseCase: SelectSshConfigUseCase,
    private val deleteSshConfigUseCase: DeleteSshConfigUseCase,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)

    val uiState = combine(
        configRepository.observeSummaries(),
        message,
    ) { configs, currentMessage ->
        ConfigListUiState(
            items = configs.map { config ->
                ConfigListItem(
                    config = config,
                    keyName = config.keyName,
                    isSelected = config.isSelected,
                )
            },
            message = currentMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ConfigListUiState(),
    )

    fun select(id: String) {
        viewModelScope.launch {
            selectSshConfigUseCase(id)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            deleteSshConfigUseCase(id)
            message.value = "Configuration deleted"
        }
    }

    fun clearMessage() {
        message.update { null }
    }
}
