package com.stansful.sshvpnclient.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Selects a validated physical network even when Android's active network is the VPN. */
internal class ValidatedPhysicalNetworkSelector(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION")
    fun select(): Network? {
        val activeNetwork = runCatching { connectivityManager.activeNetwork }.getOrNull()
        val networks = runCatching { connectivityManager.allNetworks }.getOrDefault(emptyArray())
        val candidates = networks.mapNotNull { network ->
            val capabilities = runCatching {
                connectivityManager.getNetworkCapabilities(network)
            }.getOrNull() ?: return@mapNotNull null

            ValidatedPhysicalNetworkCandidate(
                key = network,
                hasInternet = capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET,
                ),
                isValidated = capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                ),
                hasNotVpnCapability = capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
                ),
                isVpnTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            )
        }
        return selectValidatedPhysicalNetwork(candidates, activeNetwork)
    }
}

internal data class ValidatedPhysicalNetworkCandidate<K>(
    val key: K,
    val hasInternet: Boolean,
    val isValidated: Boolean,
    val hasNotVpnCapability: Boolean,
    val isVpnTransport: Boolean,
    val isEthernet: Boolean = false,
    val isWifi: Boolean = false,
    val isCellular: Boolean = false,
)

internal fun <K> selectValidatedPhysicalNetwork(
    candidates: Collection<ValidatedPhysicalNetworkCandidate<K>>,
    activeKey: K?,
): K? = candidates
    .asSequence()
    .filter { candidate ->
        candidate.hasInternet &&
            candidate.isValidated &&
            candidate.hasNotVpnCapability &&
            !candidate.isVpnTransport
    }
    .maxByOrNull { candidate ->
        when {
            candidate.key == activeKey -> 500
            candidate.isEthernet -> 400
            candidate.isWifi -> 300
            candidate.isCellular -> 200
            else -> 100
        }
    }
    ?.key
