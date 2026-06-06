package com.stansful.sshvpnclient.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkDiagnostics {
    fun describe(context: Context): List<String> {
        return try {
            val manager = context.getSystemService(ConnectivityManager::class.java)
                ?: return listOf("Network diagnostics: ConnectivityManager unavailable")
            val network = manager.activeNetwork
                ?: return listOf("Network diagnostics: no active network")
            val capabilities = manager.getNetworkCapabilities(network)
            val linkProperties = manager.getLinkProperties(network)

            buildList {
                add("Network diagnostics: active=$network")
                add("Network transports: ${capabilities.transportSummary()}")
                add("Network capabilities: ${capabilities.capabilitySummary()}")
                add(
                    "Network link: interface=${linkProperties?.interfaceName ?: "unknown"}; " +
                        "dns=${linkProperties?.dnsServers?.size ?: 0}; " +
                        "routes=${linkProperties?.routes?.size ?: 0}",
                )
            }
        } catch (error: SecurityException) {
            listOf("Network diagnostics unavailable: ${error.message ?: "missing permission"}")
        } catch (error: RuntimeException) {
            listOf("Network diagnostics failed: ${error.message ?: error::class.java.simpleName}")
        }
    }

    private fun NetworkCapabilities?.transportSummary(): String {
        if (this == null) return "unknown"
        val transports = buildList {
            if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
        }
        return transports.ifEmpty { listOf("none") }.joinToString()
    }

    private fun NetworkCapabilities?.capabilitySummary(): String {
        if (this == null) return "unknown"
        val capabilities = buildList {
            if (hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) add("internet")
            if (hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("validated")
            if (hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) add("not_vpn")
            if (hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("not_metered")
            if (hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) add("not_restricted")
        }
        return capabilities.ifEmpty { listOf("none") }.joinToString()
    }
}
