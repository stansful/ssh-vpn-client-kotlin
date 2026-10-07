package com.stansful.sshvpnclient.domain.usecase.proxy

import com.stansful.sshvpnclient.domain.model.ParsedProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import java.util.Base64
import kotlin.math.floor

/**
 * Keys of [ParsedProxyProfile.parameters] for a Hysteria 2 profile. The parser keeps only these, already
 * validated and normalized, so the Xray config builder can emit them as they are and links that differ
 * only in aliases (`hy2`/`hysteria2`, `peer`/`sni`, `pcs`/`pinSHA256`, `mport`/`ports`…) share a fingerprint.
 */
object Hysteria2Parameters {
    /** TLS server name. Absent means the host is used, as the official client does. */
    const val SNI = "sni"

    /** Comma-separated SHA-256 pins of the server certificate, 64 lowercase hex characters each. */
    const val PIN_SHA256 = "pinsha256"

    /** `1` when the link asked to skip certificate checks. Xray-core can't, so only a pin helps. */
    const val INSECURE = "insecure"

    /** [SALAMANDER] or [GECKO] (Salamander plus handshake fragmentation), always with [OBFS_PASSWORD]. */
    const val OBFS = "obfs"
    const val OBFS_PASSWORD = "obfs-password"

    /** Standard, padded base64 of the server's ECH config list. */
    const val ECH = "ech"

    /** Port hopping list such as `443,20000-50000`. */
    const val PORTS = "ports"

    /** Seconds between hops: `30` or a `10-30` range, each value at least 5. Only with [PORTS]. */
    const val HOP_INTERVAL = "hop-interval"

    /** Brutal bandwidth in Xray syntax, e.g. `100 mbps`. */
    const val UP = "up"
    const val DOWN = "down"

    const val SALAMANDER = "salamander"
    const val GECKO = "gecko"
}

/**
 * Parses `hysteria2://` and `hy2://` links as defined by the Hysteria 2 URI scheme,
 * `hysteria2://[auth@]host[:port[,port|from-to]…][/]?sni=…&insecure=1&pinSHA256=…&ech=…`
 * `&obfs=salamander|gecko&obfs-password=…#name`, plus the common client extensions `peer`, `pcs`,
 * `mport`/`ports`, `hop-interval` and `up`/`down` (`upmbps`/`downmbps`). A blank value counts as absent.
 *
 * `java.net.URI` is not used: it can't read the multi-port authority (`host:443,20000-50000`) and
 * the port is optional here (443 by default). Hysteria 2 always runs over QUIC with TLS, so transport
 * and security are fixed.
 */
internal class Hysteria2LinkParser {
    fun parse(value: String): ProxyParseResult {
        val link = splitLink(value)
        val endpoint = parseEndpoint(link.hostPort) ?: return ProxyParseResult.Failure(ProxyParseReasons.HOST_MISSING)
        val serverPorts = parsePortList(endpoint.ports.ifEmpty { "$DEFAULT_PORT" }, AUTHORITY_RANGE_SEPARATORS)
            ?: return ProxyParseResult.Failure(ProxyParseReasons.PORT_INVALID)
        val query = LinkQuery(parseShareLinkQuery(link.query, trimValues = false))
        return rejectionOf(query)
            ?: ProxyParseResult.Success(buildProfile(value, link, endpoint.host, serverPorts, query))
    }

    /**
     * Settings the official client refuses fail here too instead of being dropped: connecting without the
     * pin or the ECH config the link asks for would silently weaken it, and Xray rejects obfuscation
     * passwords shorter than 4 bytes only when it dials.
     */
    private fun rejectionOf(query: LinkQuery): ProxyParseResult.Failure? {
        val obfs = query.text(OBFS_QUERY)?.lowercase().orEmpty()
        val obfsPassword = query.secret(OBFS_PASSWORD_QUERY)
        val pins = query.text("pinsha256") ?: query.text("pcs")
        val ech = query.secret("ech")
        return when {
            obfs !in SUPPORTED_OBFS -> ProxyParseResult.Failure(ProxyParseReasons.OBFS_UNSUPPORTED, unsupported = true)
            obfs in PASSWORD_OBFS && obfsPassword == null ->
                ProxyParseResult.Failure(ProxyParseReasons.OBFS_PASSWORD_MISSING)
            obfs in PASSWORD_OBFS && obfsPassword.orEmpty().toByteArray().size < MIN_OBFS_PASSWORD_BYTES ->
                ProxyParseResult.Failure(ProxyParseReasons.OBFS_PASSWORD_TOO_SHORT)
            pins != null && normalizePins(pins) == null -> ProxyParseResult.Failure(ProxyParseReasons.PIN_INVALID)
            ech != null && normalizeEchConfigList(ech) == null ->
                ProxyParseResult.Failure(ProxyParseReasons.ECH_INVALID)
            else -> null
        }
    }

    private fun buildProfile(
        value: String,
        link: LinkParts,
        host: String,
        serverPorts: List<IntRange>,
        query: LinkQuery,
    ): ParsedProxyProfile {
        val port = serverPorts.first().first
        // Like the official client (Go `url.Userinfo`): the auth is the unescaped userinfo, `user:pass`
        // included, and a literal `+` stays a plus instead of turning into a space as in a query.
        val credential = link.userInfo?.replace("+", "%2B")?.let(::decodeShareLinkComponent)
            ?: query.secret(AUTH_QUERY).orEmpty()
        val parameters = normalizedParameters(query, serverPorts)
        val name = link.fragment?.let(::decodeShareLinkComponent)?.trim().orEmpty().ifBlank {
            "${ProxyProtocol.HYSTERIA2.name} $host:$port"
        }
        // Values are escaped so that a secret containing `|` or `=` can't imitate another parameter.
        val canonical = buildString {
            append(ProxyProtocol.HYSTERIA2.scheme).append("://")
            append(escapeCanonical(credential)).append('@').append(host.lowercase()).append(':').append(port)
            parameters.toSortedMap().forEach { (key, parameterValue) ->
                append('|').append(key).append('=').append(escapeCanonical(parameterValue))
            }
        }
        return ParsedProxyProfile(
            name = name,
            protocol = ProxyProtocol.HYSTERIA2,
            host = host,
            port = port,
            transport = ProxyTransport.HYSTERIA,
            security = ProxySecurity.TLS,
            flow = null,
            credential = credential,
            rawUri = value,
            fingerprint = sha256Hex(canonical),
            parameters = parameters,
        )
    }

    private fun normalizedParameters(query: LinkQuery, serverPorts: List<IntRange>): Map<String, String> {
        val hopPorts = if (serverPorts.size > 1 || serverPorts.single().let { it.first != it.last }) {
            serverPorts
        } else {
            (query.text("mport") ?: query.text("ports"))?.let { parsePortList(it, QUERY_RANGE_SEPARATORS) }
        }
        return buildMap {
            putIfPresent(Hysteria2Parameters.SNI, query.text("sni") ?: query.text("peer"))
            putIfPresent(Hysteria2Parameters.PIN_SHA256, normalizePins(query.text("pinsha256") ?: query.text("pcs")))
            val insecure = query.text("insecure") ?: query.text("allowinsecure") ?: query.text("allow_insecure")
            if (insecure?.lowercase() in TRUE_VALUES) put(Hysteria2Parameters.INSECURE, "1")
            val obfs = query.text(OBFS_QUERY)?.lowercase()?.takeIf { it in PASSWORD_OBFS }
            if (obfs != null) {
                put(Hysteria2Parameters.OBFS, obfs)
                put(Hysteria2Parameters.OBFS_PASSWORD, query.secret(OBFS_PASSWORD_QUERY).orEmpty())
            }
            putIfPresent(Hysteria2Parameters.ECH, normalizeEchConfigList(query.secret("ech")))
            if (hopPorts != null) {
                put(Hysteria2Parameters.PORTS, formatPortList(mergePortRanges(hopPorts)))
                val interval = query.text("hop-interval") ?: query.text("hopinterval") ?: query.text("hop_interval")
                putIfPresent(Hysteria2Parameters.HOP_INTERVAL, normalizeHopInterval(interval))
            }
            putIfPresent(Hysteria2Parameters.UP, normalizeBandwidth(query.text("up") ?: query.text("upmbps")))
            putIfPresent(Hysteria2Parameters.DOWN, normalizeBandwidth(query.text("down") ?: query.text("downmbps")))
        }
    }

    /**
     * Query values as the link carries them. Secrets keep surrounding spaces, as `url.Values.Get` does in the
     * official client; other values are trimmed. A blank value counts as absent, so it can't hide an alias.
     */
    private class LinkQuery(private val values: Map<String, String>) {
        fun secret(key: String): String? = values[key]?.takeIf(String::isNotBlank)

        fun text(key: String): String? = values[key]?.trim()?.takeIf(String::isNotEmpty)
    }

    private class LinkParts(val userInfo: String?, val hostPort: String, val query: String?, val fragment: String?)

    private class Endpoint(val host: String, val ports: String)

    private fun splitLink(value: String): LinkParts {
        val afterScheme = value.substringAfter("://", missingDelimiterValue = "")
        val beforeFragment = afterScheme.substringBefore('#')
        val authority = beforeFragment.substringBefore('?').substringBefore('/')
        return LinkParts(
            userInfo = if ('@' in authority) authority.substringBeforeLast('@') else null,
            hostPort = authority.substringAfterLast('@'),
            query = if ('?' in beforeFragment) beforeFragment.substringAfter('?') else null,
            fragment = if ('#' in afterScheme) afterScheme.substringAfter('#') else null,
        )
    }

    /** `host`, `host:443`, `[2001:db8::1]:443,20000-50000`. IPv6 keeps its brackets, as `URI.host` does. */
    private fun parseEndpoint(hostPort: String): Endpoint? {
        if (!hostPort.startsWith('[')) {
            val host = hostPort.substringBefore(':')
            return Endpoint(host, hostPort.substringAfter(':', "")).takeIf { HOST_NAME.matches(host) }
        }
        val host = hostPort.substringBefore(']') + ']'
        val rest = hostPort.substringAfter(']', missingDelimiterValue = "?")
        return when {
            !IPV6_HOST.matches(host) -> null
            rest.isEmpty() -> Endpoint(host, "")
            rest.startsWith(':') -> Endpoint(host, rest.substring(1))
            else -> null
        }
    }

    /** `443` or `443,20000-50000` into ranges in link order; null when any part is malformed or out of 1..65535. */
    private fun parsePortList(spec: String, rangeSeparators: CharArray): List<IntRange>? {
        val parts = spec.split(',').map(String::trim)
        if (parts.size > MAX_PORT_LIST_PARTS) return null
        val ranges = parts.map { part -> parsePortRange(part, rangeSeparators) ?: return null }
        return ranges.takeIf(List<IntRange>::isNotEmpty)
    }

    private fun parsePortRange(part: String, rangeSeparators: CharArray): IntRange? {
        val bounds = part.split(*rangeSeparators).map { it.trim().toIntOrNull() ?: return null }
        if (bounds.size !in 1..2 || bounds.any { it !in VALID_PORTS }) return null
        return (bounds.first()..bounds.last()).takeUnless(IntRange::isEmpty)
    }

    /**
     * Sorts and merges overlapping or adjacent ranges, as the official client does. Xray expands the hop list
     * port by port, so without this a short link repeating `1-65535` would allocate millions of addresses.
     */
    private fun mergePortRanges(ranges: List<IntRange>): List<IntRange> =
        ranges.sortedBy(IntRange::first).fold(mutableListOf()) { merged, range ->
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
            merged
        }

    private fun formatPortList(ranges: List<IntRange>): String = ranges.joinToString(",") { range ->
        if (range.first == range.last) "${range.first}" else "${range.first}-${range.last}"
    }

    /** Lowercase hex without the `:` or `-` separators the official client also accepts; null if any pin is bad. */
    private fun normalizePins(value: String?): String? {
        val pins = value?.split(',')
            ?.map { pin -> pin.replace(PIN_SEPARATORS, "").lowercase() }
            ?.filter(String::isNotEmpty)
            ?: return null
        return pins.distinct().joinToString(",").takeIf { pins.isNotEmpty() && pins.all(SHA256_HEX::matches) }
    }

    /**
     * `30`, `30s` or `10-30`. Xray rejects hops shorter than 5 s, so shorter values are raised to 5, while 0
     * means "unset" everywhere else and keeps Xray's 30 s default.
     */
    private fun normalizeHopInterval(value: String?): String? {
        val bounds = value?.lowercase()?.split('-')
            ?.map { it.trim().removeSuffix("s").toIntOrNull() ?: return null }
            ?: return null
        if (bounds.size !in 1..2 || bounds.all { it == 0 }) return null
        val seconds = bounds.map { if (it == 0) DEFAULT_HOP_INTERVAL_SECONDS else maxOf(it, MIN_HOP_INTERVAL_SECONDS) }
        val from = seconds.min()
        val to = seconds.max()
        return if (from == to) "$from" else "$from-$to"
    }

    /**
     * Brutal bandwidth in the units Xray accepts. A bare number means Mbps, as in `upmbps` and Clash configs.
     * Values Xray would reject (under 65,536 bytes/s) are dropped, which leaves congestion control to BBR.
     */
    private fun normalizeBandwidth(value: String?): String? {
        val match = value?.trim()?.lowercase()?.let(BANDWIDTH::matchEntire) ?: return null
        val amount = match.groupValues[1]
        val unit = match.groupValues[2].ifEmpty { "mbps" }
        val multiplier = BANDWIDTH_UNITS.getValue(unit.first())
        val bytesPerSecond = floor(amount.toDouble() * multiplier) / BITS_PER_BYTE
        return "$amount $unit".takeIf { bytesPerSecond in MIN_BRUTAL_BYTES_PER_SECOND..MAX_BRUTAL_BYTES_PER_SECOND }
    }

    /**
     * The official client writes the raw ECH config list as base64. Xray-core only decodes padded standard
     * base64 (anything with `://` would make it query DNS instead), so the value is re-encoded that way; null
     * when it isn't a non-empty base64 value. A `+` turned into a space by query decoding is restored.
     */
    private fun normalizeEchConfigList(value: String?): String? {
        val compact = value?.replace(' ', '+')?.trim()?.takeIf(BASE64::matches) ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(compact) }
            .recoverCatching { Base64.getUrlDecoder().decode(compact) }
            .getOrNull()
        return bytes?.takeIf(ByteArray::isNotEmpty)?.let(Base64.getEncoder()::encodeToString)
    }

    private fun escapeCanonical(value: String): String = value
        .replace("%", "%25")
        .replace("|", "%7C")
        .replace("=", "%3D")
        .replace("@", "%40")

    private fun MutableMap<String, String>.putIfPresent(key: String, value: String?) {
        if (value != null) put(key, value)
    }

    private companion object {
        const val AUTH_QUERY = "auth"
        const val OBFS_QUERY = "obfs"
        const val OBFS_PASSWORD_QUERY = "obfs-password"
        const val DEFAULT_PORT = 443
        const val MAX_PORT_LIST_PARTS = 64
        const val MIN_HOP_INTERVAL_SECONDS = 5
        const val DEFAULT_HOP_INTERVAL_SECONDS = 30
        const val MIN_OBFS_PASSWORD_BYTES = 4
        const val BITS_PER_BYTE = 8
        const val MIN_BRUTAL_BYTES_PER_SECOND = 65_536.0
        const val MAX_BRUTAL_BYTES_PER_SECOND = 1e15

        val AUTHORITY_RANGE_SEPARATORS = charArrayOf('-')
        val QUERY_RANGE_SEPARATORS = charArrayOf('-', ':')
        val VALID_PORTS = 1..65_535
        val PASSWORD_OBFS = setOf(Hysteria2Parameters.SALAMANDER, Hysteria2Parameters.GECKO)
        val SUPPORTED_OBFS = PASSWORD_OBFS + setOf("", "none", "plain")
        val TRUE_VALUES = setOf("1", "true", "t", "yes")
        val HOST_NAME = Regex("[A-Za-z0-9_](?:[A-Za-z0-9_.-]*[A-Za-z0-9_])?")
        val IPV6_HOST = Regex("\\[[0-9A-Fa-f:.]+(?:%[A-Za-z0-9_.-]+)?]")
        val SHA256_HEX = Regex("[0-9a-f]{64}")
        val PIN_SEPARATORS = Regex("[\\s:-]")
        val BASE64 = Regex("[A-Za-z0-9+/_-]+={0,2}")
        val BANDWIDTH = Regex("(\\d{1,9}(?:\\.\\d{1,6})?)\\s*(b|bps|k|kb|kbps|m|mb|mbps|g|gb|gbps|t|tb|tbps)?")

        /** Xray's `Bandwidth` multipliers: binary prefixes, and every unit is read as bits per second. */
        val BANDWIDTH_UNITS = mapOf(
            'b' to 1.0,
            'k' to 1_024.0,
            'm' to 1_048_576.0,
            'g' to 1_073_741_824.0,
            't' to 1_099_511_627_776.0,
        )
    }
}
