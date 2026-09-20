package com.stansful.sshvpnclient.vpn

/**
 * Which resolvers a DNS query from the TUN is asked, and for how long. Everything goes through the
 * SSH server, over DNS-over-TCP on port 53 and nothing else.
 *
 * The resolver the app chose goes first, then the tunnel's own - the ones the VPN interface gives
 * every app. Some apps ask the phone's own network resolver directly (a router such as 192.168.x.1,
 * a carrier resolver in 10.x or 100.64.x). The SSH server sits in another network and usually cannot
 * reach it, so a private resolver only gets a short try: an internal resolver on the server's side
 * answers within milliseconds, the phone's router never will. A resolver that stays silent is skipped
 * for a while, so only the first query pays for finding that out.
 */
internal object TunnelDnsPolicy {
    /** The VPN interface's IPv4 DNS servers, in order: the resolvers of last resort. */
    val tunnelResolvers: List<Int> = vpnAddressFamilyPlans(VpnTunnelMode.SSH)
        .first()
        .dnsServers
        .map(::parseIpv4)

    const val SILENT_RESOLVER_COOLDOWN_MS = 5 * 60_000L

    /** How long a resolver on a private address may take before the next one is asked. */
    const val PRIVATE_RESOLVER_BUDGET_MS = 1_500L

    /** A try shorter than this proves nothing about the resolver (the query had no time left). */
    const val MIN_SILENCE_EVIDENCE_MS = 1_000L

    private val privatePrefixes: List<Ipv4Prefix> = listOf(
        prefix(0, 0, 0, 0, 8), // "this network"
        prefix(10, 0, 0, 0, 8), // private
        prefix(100, 64, 0, 0, 10), // carrier-grade NAT
        prefix(127, 0, 0, 0, 8), // loopback
        prefix(169, 254, 0, 0, 16), // link-local
        prefix(172, 16, 0, 0, 12), // private
        prefix(192, 168, 0, 0, 16), // private
        prefix(224, 0, 0, 0, 3), // multicast, reserved, broadcast
    )

    /**
     * Resolvers to ask, in order, for a query an app sent to [destination]: that one, then the
     * tunnel's. Resolvers that recently stayed silent are left out, unless nothing else is left.
     */
    fun upstreamsFor(destination: Int, isSilent: (Int) -> Boolean = { false }): List<Int> {
        val candidates = (listOf(destination) + tunnelResolvers).distinct()
        return candidates.filterNot(isSilent).ifEmpty { candidates }
    }

    /**
     * The time [upstream] gets when another resolver still follows it: half of what is left, and no
     * more than [PRIVATE_RESOLVER_BUDGET_MS] for a private address.
     */
    fun attemptBudgetMs(upstream: Int, remainingMs: Long): Long {
        val half = remainingMs / 2
        return if (isPublicUnicast(upstream)) half else minOf(half, PRIVATE_RESOLVER_BUDGET_MS)
    }

    /** False for private, carrier-NAT, loopback, link-local, "this network", multicast and reserved space. */
    fun isPublicUnicast(address: Int): Boolean {
        return privatePrefixes.none { candidate -> (address and candidate.mask) == candidate.network }
    }

    private fun parseIpv4(text: String): Int {
        val parts = text.split('.').map(String::toInt)
        require(parts.size == IPV4_OCTETS && parts.all { it in 0..OCTET_MAX }) { "Not an IPv4 address: $text" }
        return parts.fold(0) { address, octet -> (address shl BITS_PER_OCTET) or octet }
    }

    private fun prefix(
        first: Int,
        second: Int,
        third: Int,
        fourth: Int,
        prefixLength: Int,
    ): Ipv4Prefix {
        val address = (first shl 24) or (second shl 16) or (third shl 8) or fourth
        val mask = -1 shl (32 - prefixLength)
        return Ipv4Prefix(network = address and mask, mask = mask)
    }

    private data class Ipv4Prefix(
        val network: Int,
        val mask: Int,
    )

    private const val IPV4_OCTETS = 4
    private const val OCTET_MAX = 255
    private const val BITS_PER_OCTET = 8
}
