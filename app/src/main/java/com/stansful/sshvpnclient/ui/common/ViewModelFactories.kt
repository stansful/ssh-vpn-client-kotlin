package com.stansful.sshvpnclient.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.ui.configedit.EditConfigViewModel
import com.stansful.sshvpnclient.ui.configs.ConfigListViewModel
import com.stansful.sshvpnclient.ui.keyedit.EditKeyViewModel
import com.stansful.sshvpnclient.ui.keys.KeyListViewModel
import com.stansful.sshvpnclient.ui.main.MainViewModel

class AppViewModelFactory(
    private val container: AppContainer,
    private val configId: String? = null,
    private val keyId: String? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(
                appSettingsRepository = container.appSettingsRepository,
                configRepository = container.sshConfigRepository,
                keyRepository = container.sshPrivateKeyRepository,
                vpnConnectionRepository = container.vpnConnectionRepository,
                connectVpnUseCase = container.connectVpnUseCase,
                disconnectVpnUseCase = container.disconnectVpnUseCase,
                observeVpnConnectionStateUseCase = container.observeVpnConnectionStateUseCase,
            )

            modelClass.isAssignableFrom(ConfigListViewModel::class.java) -> ConfigListViewModel(
                configRepository = container.sshConfigRepository,
                keyRepository = container.sshPrivateKeyRepository,
                selectSshConfigUseCase = container.selectSshConfigUseCase,
                deleteSshConfigUseCase = container.deleteSshConfigUseCase,
            )

            modelClass.isAssignableFrom(EditConfigViewModel::class.java) -> EditConfigViewModel(
                configId = configId,
                addSshConfigUseCase = container.addSshConfigUseCase,
                updateSshConfigUseCase = container.updateSshConfigUseCase,
                getSshConfigByIdUseCase = container.getSshConfigByIdUseCase,
                getSshPrivateKeyListUseCase = container.getSshPrivateKeyListUseCase,
            )

            modelClass.isAssignableFrom(KeyListViewModel::class.java) -> KeyListViewModel(
                getSshPrivateKeyListUseCase = container.getSshPrivateKeyListUseCase,
                getSshPrivateKeyUsageCountUseCase = container.getSshPrivateKeyUsageCountUseCase,
                deleteSshPrivateKeyUseCase = container.deleteSshPrivateKeyUseCase,
            )

            modelClass.isAssignableFrom(EditKeyViewModel::class.java) -> EditKeyViewModel(
                keyId = keyId,
                addSshPrivateKeyUseCase = container.addSshPrivateKeyUseCase,
                updateSshPrivateKeyUseCase = container.updateSshPrivateKeyUseCase,
                getSshPrivateKeyByIdUseCase = container.getSshPrivateKeyByIdUseCase,
            )

            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        } as T
    }
}
