package com.stansful.sshvpnclient.vpn

import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.VpnMode
import kotlinx.coroutines.CancellationException

class VpnTunnelManager {
    private val lock = Any()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var activeOwner: Any? = null

    fun isEstablished(owner: Any): Boolean = synchronized(lock) {
        activeOwner === owner && vpnInterface?.fileDescriptor?.valid() == true
    }

    fun establish(
        owner: Any,
        lease: VpnRuntimeLease,
        service: VpnService,
        config: SshConfig,
        appSettings: AppSettings,
        underlyingNetwork: Network,
        log: (String) -> Unit = {},
    ): ParcelFileDescriptor {
        return establish(
            owner = owner,
            lease = lease,
            service = service,
            sessionName = VPN_SESSION_NAME,
            appSettings = appSettings,
            mode = VpnTunnelMode.SSH,
            underlyingNetwork = underlyingNetwork,
            log = log,
        )
    }

    fun establish(
        owner: Any,
        lease: VpnRuntimeLease,
        service: VpnService,
        sessionName: String,
        appSettings: AppSettings,
        mode: VpnTunnelMode,
        underlyingNetwork: Network,
        log: (String) -> Unit = {},
    ): ParcelFileDescriptor {
        require(lease.owner === owner) { "VPN owner must match runtime lease" }
        return lease.requireCurrent {
            synchronized(lock) {
                if (activeOwner != null && activeOwner !== owner) {
                    throw VpnConnectionException("VPN runtime belongs to another service instance")
                }
                closeActiveLocked()

                val builder = service.Builder()
                    .setSession(sessionName)
                    .setMtu(mode.mtu)
                    .setBlocking(mode.blocking)
                    .setUnderlyingNetworks(arrayOf(underlyingNetwork))

                vpnAddressFamilyPlans(mode).forEach { family ->
                    builder.addAddress(family.address, family.addressPrefix)
                    builder.addRoute(family.defaultRoute, family.routePrefix)
                    family.dnsServers.forEach(builder::addDnsServer)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                applySplitTunnelSettings(builder, appSettings, log)

                val established = builder.establish()
                    ?.also {
                        vpnInterface = it
                        activeOwner = owner
                    }
                    ?: throw VpnConnectionException("VPN permission denied")
                if (!lease.isCurrent()) {
                    closeActiveLocked()
                    throw CancellationException("VPN runtime lease was superseded")
                }
                established
            }
        }
    }

    fun updateUnderlyingNetwork(
        owner: Any,
        service: VpnService,
        network: Network?,
    ): Boolean = synchronized(lock) {
        if (activeOwner !== owner || vpnInterface?.fileDescriptor?.valid() != true) {
            return@synchronized false
        }
        service.setUnderlyingNetworks(network?.let { arrayOf(it) })
    }

    fun close(owner: Any): Boolean = synchronized(lock) {
        if (activeOwner !== owner) return@synchronized false
        closeActiveLocked()
        true
    }

    private fun closeActiveLocked() {
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        activeOwner = null
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
                    throw VpnConnectionException("No apps selected")
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
                    throw VpnConnectionException("No apps selected")
                }
            }
        }
    }

    private companion object {
        const val VPN_SESSION_NAME = "Secure connection"
    }
}

enum class VpnTunnelMode(
    val mtu: Int,
    val blocking: Boolean,
) {
    SSH(mtu = 8_500, blocking = true),
    XRAY(mtu = 1_500, blocking = false),
}

internal data class VpnAddressFamilyPlan(
    val address: String,
    val addressPrefix: Int,
    val defaultRoute: String,
    val routePrefix: Int,
    val dnsServers: List<String>,
)

internal fun vpnAddressFamilyPlans(mode: VpnTunnelMode): List<VpnAddressFamilyPlan> {
    val ipv4 = VpnAddressFamilyPlan(
        address = "10.10.0.2",
        addressPrefix = 32,
        defaultRoute = "0.0.0.0",
        routePrefix = 0,
        dnsServers = listOf("1.1.1.1", "8.8.8.8"),
    )
    if (mode != VpnTunnelMode.XRAY) return listOf(ipv4)
    return listOf(
        ipv4,
        VpnAddressFamilyPlan(
            address = "fd00:10:10::2",
            addressPrefix = 128,
            defaultRoute = "::",
            routePrefix = 0,
            dnsServers = listOf("2606:4700:4700::1111", "2001:4860:4860::8888"),
        ),
    )
}
