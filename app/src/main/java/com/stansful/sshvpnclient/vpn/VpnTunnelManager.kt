package com.stansful.sshvpnclient.vpn

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.VpnMode

class VpnTunnelManager {
    private var vpnInterface: ParcelFileDescriptor? = null

    fun establish(
        service: VpnService,
        config: SshConfig,
        appSettings: AppSettings,
        log: (String) -> Unit = {},
    ): ParcelFileDescriptor {
        close()

        val builder = service.Builder()
            .setSession("SSH VPN: ${config.name}")
            .setMtu(MTU)
            .addAddress(PRIVATE_ADDRESS, PRIVATE_ADDRESS_PREFIX)
            .addRoute(DEFAULT_ROUTE, DEFAULT_ROUTE_PREFIX)
            .addDnsServer(CLOUDFLARE_DNS)
            .addDnsServer(GOOGLE_DNS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        applySplitTunnelSettings(builder, appSettings, log)

        return builder.establish()
            ?.also { vpnInterface = it }
            ?: throw VpnConnectionException("VPN permission denied")
    }

    fun close() {
        vpnInterface?.close()
        vpnInterface = null
    }

    private fun applySplitTunnelSettings(
        builder: VpnService.Builder,
        appSettings: AppSettings,
        log: (String) -> Unit,
    ) {
        when (appSettings.vpnMode) {
            VpnMode.PROXY -> {
                log("VPN app routing mode: proxy; all applications are routed through VPN")
            }

            VpnMode.SELECTED_APPS -> {
                if (appSettings.selectedAppPackages.isEmpty()) {
                    throw VpnConnectionException("Нет выбранных приложений")
                }
                log(
                    "VPN app routing mode: selected-apps; " +
                        "${appSettings.selectedAppPackages.size} selected applications",
                )
                var allowedApplications = 0
                appSettings.selectedAppPackages.sorted().forEach { packageName ->
                    try {
                        builder.addAllowedApplication(packageName)
                        allowedApplications += 1
                    } catch (error: Exception) {
                        log("Skipping selected app '$packageName': ${error.message ?: error::class.java.simpleName}")
                    }
                }
                if (allowedApplications == 0) {
                    throw VpnConnectionException("Нет выбранных приложений")
                }
            }
        }
    }

    private companion object {
        const val MTU = 1500
        const val PRIVATE_ADDRESS = "10.10.0.2"
        const val PRIVATE_ADDRESS_PREFIX = 32
        const val DEFAULT_ROUTE = "0.0.0.0"
        const val DEFAULT_ROUTE_PREFIX = 0
        const val CLOUDFLARE_DNS = "1.1.1.1"
        const val GOOGLE_DNS = "8.8.8.8"
    }
}
