package com.stansful.sshvpnclient

import android.content.Context
import androidx.room.Room
import com.stansful.sshvpnclient.data.apps.PackageManagerInstalledAppsRepository
import com.stansful.sshvpnclient.data.config.RoomSshConfigRepository
import com.stansful.sshvpnclient.data.key.RoomSshPrivateKeyRepository
import com.stansful.sshvpnclient.data.local.AppDatabase
import com.stansful.sshvpnclient.data.local.MIGRATION_1_2
import com.stansful.sshvpnclient.data.local.MIGRATION_2_3
import com.stansful.sshvpnclient.data.local.MIGRATION_3_4
import com.stansful.sshvpnclient.data.local.InMemoryVpnConnectionRepository
import com.stansful.sshvpnclient.data.local.SmartConnectStateStore
import com.stansful.sshvpnclient.data.proxy.RoomProxyProfileRepository
import com.stansful.sshvpnclient.data.proxy.PublicProxySourceSynchronizer
import com.stansful.sshvpnclient.data.smart.RoomSmartProxyProfileRepository
import com.stansful.sshvpnclient.data.smart.IsolatedSmartProxySourceSynchronizer
import com.stansful.sshvpnclient.data.secret.TinkSecretStorage
import com.stansful.sshvpnclient.data.settings.SharedPreferencesAppSettingsRepository
import com.stansful.sshvpnclient.data.update.AndroidAppUpdateDownloader
import com.stansful.sshvpnclient.data.update.DefaultAppUpdateCoordinator
import com.stansful.sshvpnclient.data.update.GitHubAppUpdateRepository
import com.stansful.sshvpnclient.data.update.GitHubXrayCoreUpdateRepository
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.AppUpdateCoordinator
import com.stansful.sshvpnclient.domain.repository.AppUpdateDownloader
import com.stansful.sshvpnclient.domain.repository.AppUpdateRepository
import com.stansful.sshvpnclient.domain.repository.InstalledAppsRepository
import com.stansful.sshvpnclient.domain.repository.ProxyProfileRepository
import com.stansful.sshvpnclient.domain.repository.ProxySourceSynchronizer
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.repository.SshPrivateKeyRepository
import com.stansful.sshvpnclient.domain.repository.SmartProxyProfileRepository
import com.stansful.sshvpnclient.domain.repository.SmartProxySourceSynchronizer
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.domain.repository.XrayCoreUpdateRepository
import com.stansful.sshvpnclient.domain.usecase.config.AddSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.config.DeleteSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.config.GetSshConfigByIdUseCase
import com.stansful.sshvpnclient.domain.usecase.config.SelectSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.config.UpdateSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.key.AddSshPrivateKeyUseCase
import com.stansful.sshvpnclient.domain.usecase.key.DeleteSshPrivateKeyUseCase
import com.stansful.sshvpnclient.domain.usecase.key.GetSshPrivateKeyByIdUseCase
import com.stansful.sshvpnclient.domain.usecase.key.GetSshPrivateKeyListUseCase
import com.stansful.sshvpnclient.domain.usecase.key.UpdateSshPrivateKeyUseCase
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectProxyVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectSmartVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.DisconnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.ObserveVpnConnectionStateUseCase
import com.stansful.sshvpnclient.vpn.SshConnectionManager
import com.stansful.sshvpnclient.vpn.SmartConnectCatalogManager
import com.stansful.sshvpnclient.vpn.Tun2SocksManager
import com.stansful.sshvpnclient.vpn.VpnTunnelManager
import com.stansful.sshvpnclient.vpn.VpnRuntimeLeaseRegistry
import com.stansful.sshvpnclient.xray.XrayConfigBuilder
import com.stansful.sshvpnclient.xray.XrayCoreBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val applicationContext: Context
        get() = appContext

    private val database: AppDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "ssh-vpn-client.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }

    private val secretStorage by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TinkSecretStorage(appContext)
    }

    val sshConfigRepository: SshConfigRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomSshConfigRepository(
            dao = database.sshConfigDao(),
            secretStorage = secretStorage,
        )
    }

    val sshPrivateKeyRepository: SshPrivateKeyRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomSshPrivateKeyRepository(
            keyDao = database.sshPrivateKeyDao(),
            configDao = database.sshConfigDao(),
            secretStorage = secretStorage,
        )
    }

    val vpnConnectionRepository: VpnConnectionRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        InMemoryVpnConnectionRepository(appContext, applicationScope)
    }
    val appSettingsRepository: AppSettingsRepository = SharedPreferencesAppSettingsRepository(appContext)
    val installedAppsRepository: InstalledAppsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PackageManagerInstalledAppsRepository(appContext)
    }
    val proxyShareLinkParser by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ProxyShareLinkParser() }
    val proxyProfileRepository: ProxyProfileRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomProxyProfileRepository(
            dao = database.proxyProfileDao(),
            secretStorage = secretStorage,
            parser = proxyShareLinkParser,
        )
    }
    val proxySourceSynchronizer: ProxySourceSynchronizer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PublicProxySourceSynchronizer(
            context = appContext,
            proxyProfileRepository = proxyProfileRepository,
        )
    }
    val smartProxyProfileRepository: SmartProxyProfileRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        RoomSmartProxyProfileRepository(
            dao = database.smartProxyProfileDao(),
            secretStorage = secretStorage,
            parser = proxyShareLinkParser,
        )
    }
    val smartProxySourceSynchronizer: SmartProxySourceSynchronizer by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        IsolatedSmartProxySourceSynchronizer(
            context = appContext,
            profileRepository = smartProxyProfileRepository,
        )
    }
    val smartConnectStateStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SmartConnectStateStore(appContext)
    }
    val appUpdateRepository: AppUpdateRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GitHubAppUpdateRepository(
            context = appContext,
            currentVersionName = BuildConfig.VERSION_NAME,
        )
    }
    val appUpdateDownloader: AppUpdateDownloader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidAppUpdateDownloader(
            context = appContext,
            applicationScope = applicationScope,
        )
    }
    val appUpdateCoordinator: AppUpdateCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DefaultAppUpdateCoordinator(
            repository = appUpdateRepository,
            downloader = appUpdateDownloader,
            applicationScope = applicationScope,
        )
    }
    val xrayCoreUpdateRepository: XrayCoreUpdateRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GitHubXrayCoreUpdateRepository(context = appContext)
    }

    val sshConnectionManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SshConnectionManager() }
    val vpnRuntimeLeaseRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { VpnRuntimeLeaseRegistry() }
    val vpnTunnelManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { VpnTunnelManager() }
    val tun2SocksManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Tun2SocksManager() }
    val xrayConfigBuilder by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        XrayConfigBuilder(proxyShareLinkParser)
    }
    val xrayCoreBridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        XrayCoreBridge(appContext, xrayConfigBuilder)
    }
    val smartConnectCatalogManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SmartConnectCatalogManager(
            profileRepository = smartProxyProfileRepository,
            sourceSynchronizer = smartProxySourceSynchronizer,
            xrayCoreBridge = xrayCoreBridge,
            stateStore = smartConnectStateStore,
            log = vpnConnectionRepository::appendDiagnostic,
        )
    }

    val addSshConfigUseCase by lazy { AddSshConfigUseCase(sshConfigRepository) }
    val updateSshConfigUseCase by lazy { UpdateSshConfigUseCase(sshConfigRepository) }
    val deleteSshConfigUseCase by lazy { DeleteSshConfigUseCase(sshConfigRepository) }
    val getSshConfigByIdUseCase by lazy { GetSshConfigByIdUseCase(sshConfigRepository) }
    val selectSshConfigUseCase by lazy { SelectSshConfigUseCase(sshConfigRepository) }

    val addSshPrivateKeyUseCase by lazy { AddSshPrivateKeyUseCase(sshPrivateKeyRepository) }
    val updateSshPrivateKeyUseCase by lazy { UpdateSshPrivateKeyUseCase(sshPrivateKeyRepository) }
    val deleteSshPrivateKeyUseCase by lazy { DeleteSshPrivateKeyUseCase(sshPrivateKeyRepository) }
    val getSshPrivateKeyByIdUseCase by lazy { GetSshPrivateKeyByIdUseCase(sshPrivateKeyRepository) }
    val getSshPrivateKeyListUseCase by lazy { GetSshPrivateKeyListUseCase(sshPrivateKeyRepository) }

    val connectVpnUseCase by lazy {
        ConnectVpnUseCase(
            context = appContext,
            configRepository = sshConfigRepository,
            keyRepository = sshPrivateKeyRepository,
            vpnConnectionRepository = vpnConnectionRepository,
            appSettingsRepository = appSettingsRepository,
        )
    }
    val connectProxyVpnUseCase by lazy {
        ConnectProxyVpnUseCase(
            context = appContext,
            proxyProfileRepository = proxyProfileRepository,
            appSettingsRepository = appSettingsRepository,
            vpnConnectionRepository = vpnConnectionRepository,
        )
    }
    val connectSmartVpnUseCase by lazy {
        ConnectSmartVpnUseCase(
            context = appContext,
            appSettingsRepository = appSettingsRepository,
            vpnConnectionRepository = vpnConnectionRepository,
            smartConnectStateStore = smartConnectStateStore,
        )
    }
    val disconnectVpnUseCase by lazy {
        DisconnectVpnUseCase(
            context = appContext,
            vpnConnectionRepository = vpnConnectionRepository,
        )
    }
    val observeVpnConnectionStateUseCase by lazy {
        ObserveVpnConnectionStateUseCase(vpnConnectionRepository)
    }
}
