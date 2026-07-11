package com.stansful.sshvpnclient.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.Closeable
import java.net.InetAddress
import java.net.Inet6Address
import java.net.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Tracks the best usable physical network to use below the VPN.
 *
 * Keeping this selection outside the VPN routing table is important during a hot reconnect:
 * once the TUN default route exists, the process default network can be the VPN itself.
 * Android validation is preferred, but INTERNET+NOT_VPN cellular remains a usable fallback while
 * the platform validation probe is delayed or blocked by the carrier.
 */
class UnderlyingNetworkMonitor(
    context: Context,
    private val onNetworkChanged: (old: Network?, new: Network?) -> Unit = { _, _ -> },
) : Closeable {
    private val connectivityManager =
        requireNotNull(context.applicationContext.getSystemService(ConnectivityManager::class.java)) {
            "ConnectivityManager unavailable"
        }
    private val lock = Any()
    private val capabilitiesByNetwork = LinkedHashMap<Network, NetworkCapabilities>()
    private var registered = false
    private var hasInitialSelection = false
    private val selectedNetworkState = MutableStateFlow<Network?>(null)

    @Volatile
    private var selectedNetwork: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshNetwork(network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateNetwork(network, capabilities)
        }

        override fun onLost(network: Network) {
            val change = synchronized(lock) {
                capabilitiesByNetwork.remove(network)
                selectLocked()
            }
            change?.let { (old, new) -> onNetworkChanged(old, new) }
        }
    }

    fun start() {
        synchronized(lock) {
            if (registered) return
            registered = true
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, callback)
            // Seed synchronously so the very first SSH socket does not depend on callback timing.
            // Further eligible networks arrive through the registered request callback.
            connectivityManager.activeNetwork?.let(::refreshNetwork)
        } catch (error: RuntimeException) {
            synchronized(lock) { registered = false }
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            throw error
        }
    }

    fun currentNetwork(): Network? = selectedNetwork

    fun dnsEndpointFor(network: Network): String? {
        return runCatching {
            connectivityManager.getLinkProperties(network)
                ?.dnsServers
                ?.firstOrNull()
                ?.let(::dnsEndpoint)
        }.getOrNull()
    }

    fun requireUsableNetwork(): Network {
        return selectedNetwork
            ?: throw VpnConnectionException("No usable non-VPN network available")
    }

    /** Suspends without polling until Android reports an eligible physical network. */
    suspend fun awaitUsableNetwork(): Network {
        while (true) {
            val candidate = selectedNetworkState.filterNotNull().first()
            if (selectedNetwork == candidate) return candidate
        }
    }

    override fun close() {
        val shouldUnregister = synchronized(lock) {
            if (!registered) return@synchronized false
            registered = false
            capabilitiesByNetwork.clear()
            selectedNetwork = null
            selectedNetworkState.value = null
            true
        }
        if (shouldUnregister) {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }

    private fun refreshNetwork(network: Network) {
        val capabilities = runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull()
        if (capabilities == null) {
            val change = synchronized(lock) {
                capabilitiesByNetwork.remove(network)
                selectLocked()
            }
            change?.let { (old, new) -> onNetworkChanged(old, new) }
        } else {
            updateNetwork(network, capabilities)
        }
    }

    private fun updateNetwork(network: Network, capabilities: NetworkCapabilities) {
        val change = synchronized(lock) {
            if (!registered) return@synchronized null
            capabilitiesByNetwork[network] = capabilities
            selectLocked()
        }
        change?.let { (old, new) -> onNetworkChanged(old, new) }
    }

    private fun selectLocked(): Pair<Network?, Network?>? {
        val activeNetwork = runCatching { connectivityManager.activeNetwork }.getOrNull()
        val candidates = capabilitiesByNetwork.map { (network, capabilities) ->
            UnderlyingNetworkCandidate(
                key = network,
                hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                isNotVpn = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                isNotMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            )
        }
        val previous = selectedNetwork
        val selected = selectUnderlyingNetwork(
            candidates = candidates,
            activeKey = activeNetwork,
            currentKey = previous,
        )
        if (previous == selected) return null
        selectedNetwork = selected
        selectedNetworkState.value = selected
        // Initial discovery establishes a baseline; it is not a handoff and must not tear down a
        // connection that may have started while NetworkCallback delivery was queued.
        if (!hasInitialSelection && selected != null) {
            hasInitialSelection = true
            return null
        }
        return previous to selected
    }
}

/** Captures one network for DNS resolution and socket binding as one atomic routing decision. */
internal interface VpnSocketRouteProvider {
    fun routeFor(host: String): VpnSocketRoute
}

internal interface VpnSocketRoute {
    val addresses: List<InetAddress>
    val description: String

    fun bind(socket: Socket)
}

/**
 * This object deliberately implements the existing JSch socket-protector function type. The
 * socket factory can discover the richer routing interface without widening SshConnectionManager's
 * public API.
 */
internal class UnderlyingNetworkSocketProtector(
    private val protectSocket: (Socket) -> Boolean,
    private val networkProvider: () -> Network,
) : (Socket) -> Boolean, VpnSocketRouteProvider {
    override fun invoke(socket: Socket): Boolean = protectSocket(socket)

    override fun routeFor(host: String): VpnSocketRoute {
        val network = networkProvider()
        val addresses = network.getAllByName(host).toList()
        if (addresses.isEmpty()) throw java.net.UnknownHostException(host)
        return object : VpnSocketRoute {
            override val addresses: List<InetAddress> = addresses
            override val description: String = network.toString()

            override fun bind(socket: Socket) {
                network.bindSocket(socket)
            }
        }
    }
}

internal data class UnderlyingNetworkCandidate<K>(
    val key: K,
    val hasInternet: Boolean,
    val isValidated: Boolean,
    val isNotVpn: Boolean,
    val isWifi: Boolean = false,
    val isEthernet: Boolean = false,
    val isCellular: Boolean = false,
    val isNotMetered: Boolean = false,
)

/** Pure selection policy kept independent from Android so handoff behavior is unit-testable. */
internal fun <K> selectUnderlyingNetwork(
    candidates: Collection<UnderlyingNetworkCandidate<K>>,
    activeKey: K?,
    currentKey: K?,
): K? {
    val eligible = candidates.filter { it.hasInternet && it.isNotVpn }
    // Before the VPN is established Android's active physical network is authoritative. Once the
    // VPN becomes active it is no longer an eligible candidate, so keep the captured physical
    // network sticky until it is actually lost/ineligible instead of drifting back to Wi-Fi.
    eligible.firstOrNull { candidate -> candidate.key == activeKey }?.let { return it.key }
    eligible.firstOrNull { candidate -> candidate.key == currentKey }?.let { return it.key }
    return eligible
        .asSequence()
        .maxWithOrNull(
            compareBy<UnderlyingNetworkCandidate<K>> { candidate ->
                (if (candidate.isValidated) 1_000 else 0) + when {
                    candidate.isEthernet -> 400
                    candidate.isWifi -> 300
                    candidate.isCellular -> 200
                    else -> 100
                } + (if (candidate.isNotMetered) 20 else 0)
            }.thenBy { it.key.hashCode() },
        )
        ?.key
}

internal fun dnsEndpoint(address: InetAddress): String {
    val host = requireNotNull(address.hostAddress) { "DNS address has no numeric representation" }
    return if (address is Inet6Address) "[$host]:53" else "$host:53"
}
