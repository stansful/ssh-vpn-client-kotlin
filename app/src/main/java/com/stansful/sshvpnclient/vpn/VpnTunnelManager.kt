package com.stansful.sshvpnclient.vpn

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.stansful.sshvpnclient.domain.model.SshConfig

class VpnTunnelManager {
    private var vpnInterface: ParcelFileDescriptor? = null

    fun establish(
        service: VpnService,
        config: SshConfig,
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

        return builder.establish()
            ?.also { vpnInterface = it }
            ?: throw VpnConnectionException("VPN permission denied")
    }

    fun close() {
        vpnInterface?.close()
        vpnInterface = null
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
