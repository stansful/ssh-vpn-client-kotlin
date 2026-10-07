package com.stansful.sshvpnclient.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.ui.apppicker.AppPickerViewModel
import com.stansful.sshvpnclient.ui.configs.ConfigListViewModel
import com.stansful.sshvpnclient.ui.main.MainViewModel
import com.stansful.sshvpnclient.ui.opensource.OpenSourceViewModel
import com.stansful.sshvpnclient.ui.smartconnect.SmartConnectViewModel

/**
 * Builds the ViewModels that need only the [container]: the activity-scoped ones and the screen-scoped
 * Servers list and App routing picker. The editors and the Keys list are built at their routes.
 */
class AppViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(SmartConnectViewModel::class.java) -> SmartConnectViewModel(
                proxyProfileRepository = container.smartProxyProfileRepository,
                smartConnectStateStore = container.smartConnectStateStore,
                appSettingsRepository = container.appSettingsRepository,
                vpnConnectionRepository = container.vpnConnectionRepository,
                connectSmartVpnUseCase = container.connectSmartVpnUseCase,
                disconnectVpnUseCase = container.disconnectVpnUseCase,
                xrayCoreBridge = container.xrayCoreBridge,
                xrayCoreUpdateRepository = container.xrayCoreUpdateRepository,
                appUpdateCoordinator = container.appUpdateCoordinator,
                xrayCoreInstallInProgress = container.xrayCoreInstallInProgress,
            )

            modelClass.isAssignableFrom(OpenSourceViewModel::class.java) -> OpenSourceViewModel(
                proxyProfileRepository = container.proxyProfileRepository,
                proxySourceSynchronizer = container.proxySourceSynchronizer,
                xrayCoreBridge = container.xrayCoreBridge,
                appSettingsRepository = container.appSettingsRepository,
                connectProxyVpnUseCase = container.connectProxyVpnUseCase,
                disconnectVpnUseCase = container.disconnectVpnUseCase,
                vpnConnectionRepository = container.vpnConnectionRepository,
                appUpdateCoordinator = container.appUpdateCoordinator,
                xrayCoreUpdateRepository = container.xrayCoreUpdateRepository,
                xrayCoreInstallInProgress = container.xrayCoreInstallInProgress,
                isAutoActive = { container.smartConnectStateStore.desiredActive },
            )

            modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(
                appSettingsRepository = container.appSettingsRepository,
                configRepository = container.sshConfigRepository,
                vpnConnectionRepository = container.vpnConnectionRepository,
                connectVpnUseCase = container.connectVpnUseCase,
                disconnectVpnUseCase = container.disconnectVpnUseCase,
                selectSshConfigUseCase = container.selectSshConfigUseCase,
                sshConnectionManager = container.sshConnectionManager,
                observeVpnConnectionStateUseCase = container.observeVpnConnectionStateUseCase,
                appUpdateCoordinator = container.appUpdateCoordinator,
            )

            modelClass.isAssignableFrom(AppPickerViewModel::class.java) -> AppPickerViewModel(
                appSettingsRepository = container.appSettingsRepository,
                installedAppsRepository = container.installedAppsRepository,
            )

            modelClass.isAssignableFrom(ConfigListViewModel::class.java) -> ConfigListViewModel(
                configRepository = container.sshConfigRepository,
                selectSshConfigUseCase = container.selectSshConfigUseCase,
                deleteSshConfigUseCase = container.deleteSshConfigUseCase,
            )

            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        } as T
    }
}
