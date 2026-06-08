package com.stansful.sshvpnclient

import android.content.Context
import androidx.room.Room
import com.stansful.sshvpnclient.data.apps.PackageManagerInstalledAppsRepository
import com.stansful.sshvpnclient.data.config.RoomSshConfigRepository
import com.stansful.sshvpnclient.data.key.RoomSshPrivateKeyRepository
import com.stansful.sshvpnclient.data.local.AppDatabase
import com.stansful.sshvpnclient.data.local.InMemoryVpnConnectionRepository
import com.stansful.sshvpnclient.data.secret.TinkSecretStorage
import com.stansful.sshvpnclient.data.settings.SharedPreferencesAppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.InstalledAppsRepository
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.repository.SshPrivateKeyRepository
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.domain.usecase.config.AddSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.config.DeleteSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.config.GetSshConfigByIdUseCase
import com.stansful.sshvpnclient.domain.usecase.config.GetSshConfigListUseCase
import com.stansful.sshvpnclient.domain.usecase.config.SelectSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.config.UpdateSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.key.AddSshPrivateKeyUseCase
import com.stansful.sshvpnclient.domain.usecase.key.DeleteSshPrivateKeyUseCase
import com.stansful.sshvpnclient.domain.usecase.key.GetSshPrivateKeyByIdUseCase
import com.stansful.sshvpnclient.domain.usecase.key.GetSshPrivateKeyListUseCase
import com.stansful.sshvpnclient.domain.usecase.key.GetSshPrivateKeyUsageCountUseCase
import com.stansful.sshvpnclient.domain.usecase.key.UpdateSshPrivateKeyUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.DisconnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.ObserveVpnConnectionStateUseCase
import com.stansful.sshvpnclient.vpn.SshConnectionManager
import com.stansful.sshvpnclient.vpn.Tun2SocksManager
import com.stansful.sshvpnclient.vpn.VpnTunnelManager

class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext

    private val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "ssh-vpn-client.db",
    ).build()

    private val secretStorage = TinkSecretStorage(appContext)

    val sshConfigRepository: SshConfigRepository = RoomSshConfigRepository(
        dao = database.sshConfigDao(),
        secretStorage = secretStorage,
    )

    val sshPrivateKeyRepository: SshPrivateKeyRepository = RoomSshPrivateKeyRepository(
        keyDao = database.sshPrivateKeyDao(),
        configDao = database.sshConfigDao(),
        secretStorage = secretStorage,
    )

    val vpnConnectionRepository: VpnConnectionRepository = InMemoryVpnConnectionRepository(appContext)
    val appSettingsRepository: AppSettingsRepository = SharedPreferencesAppSettingsRepository(appContext)
    val installedAppsRepository: InstalledAppsRepository = PackageManagerInstalledAppsRepository(appContext)

    val sshConnectionManager = SshConnectionManager()
    val vpnTunnelManager = VpnTunnelManager()
    val tun2SocksManager = Tun2SocksManager()

    val addSshConfigUseCase = AddSshConfigUseCase(sshConfigRepository)
    val updateSshConfigUseCase = UpdateSshConfigUseCase(sshConfigRepository)
    val deleteSshConfigUseCase = DeleteSshConfigUseCase(sshConfigRepository)
    val getSshConfigByIdUseCase = GetSshConfigByIdUseCase(sshConfigRepository)
    val getSshConfigListUseCase = GetSshConfigListUseCase(sshConfigRepository)
    val selectSshConfigUseCase = SelectSshConfigUseCase(sshConfigRepository)

    val addSshPrivateKeyUseCase = AddSshPrivateKeyUseCase(sshPrivateKeyRepository)
    val updateSshPrivateKeyUseCase = UpdateSshPrivateKeyUseCase(sshPrivateKeyRepository)
    val deleteSshPrivateKeyUseCase = DeleteSshPrivateKeyUseCase(sshPrivateKeyRepository)
    val getSshPrivateKeyByIdUseCase = GetSshPrivateKeyByIdUseCase(sshPrivateKeyRepository)
    val getSshPrivateKeyListUseCase = GetSshPrivateKeyListUseCase(sshPrivateKeyRepository)
    val getSshPrivateKeyUsageCountUseCase =
        GetSshPrivateKeyUsageCountUseCase(sshPrivateKeyRepository)

    val connectVpnUseCase = ConnectVpnUseCase(
        context = appContext,
        configRepository = sshConfigRepository,
        keyRepository = sshPrivateKeyRepository,
        vpnConnectionRepository = vpnConnectionRepository,
        appSettingsRepository = appSettingsRepository,
    )
    val disconnectVpnUseCase = DisconnectVpnUseCase(appContext)
    val observeVpnConnectionStateUseCase = ObserveVpnConnectionStateUseCase(vpnConnectionRepository)
}
