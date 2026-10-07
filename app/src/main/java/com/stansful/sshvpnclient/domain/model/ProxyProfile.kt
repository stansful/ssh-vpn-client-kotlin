package com.stansful.sshvpnclient.domain.model

data class ProxyProfile(
    val id: String,
    val name: String,
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    val transport: ProxyTransport,
    val security: ProxySecurity,
    val flow: String?,
    val source: ProxyProfileSource,
    val sourceUrl: String?,
    val rawUri: String,
    val fingerprint: String,
    val isSelected: Boolean,
    val isPinned: Boolean,
    val isStale: Boolean,
    val lastTestStatus: ProxyTestStatus,
    val lastLatencyMs: Long?,
    val lastTestAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSeenAt: Long,
)

data class ProxyProfileSummary(
    val id: String,
    val name: String,
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    val transport: ProxyTransport,
    val security: ProxySecurity,
    val flow: String?,
    val fingerprint: String,
    val source: ProxyProfileSource,
    val isSelected: Boolean,
    val isPinned: Boolean,
    val isStale: Boolean,
    val lastTestStatus: ProxyTestStatus,
    val lastLatencyMs: Long?,
    val updatedAt: Long,
)

data class ParsedProxyProfile(
    val name: String,
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    val transport: ProxyTransport,
    val security: ProxySecurity,
    val flow: String?,
    val credential: String,
    val rawUri: String,
    val fingerprint: String,
    val parameters: Map<String, String>,
)

/**
 * The `name` of each entry is persisted in Room and read back with `enumValueOf`, so it must never
 * be renamed. [scheme] is the canonical share-link scheme, [aliases] are other accepted schemes and
 * [xrayProtocol] is the outbound `protocol` value understood by Xray-core.
 */
enum class ProxyProtocol(
    val scheme: String,
    val xrayProtocol: String = scheme,
    val aliases: Set<String> = emptySet(),
) {
    VLESS("vless"),
    VMESS("vmess"),
    TROJAN("trojan"),

    /** Hysteria 2 over QUIC: Xray names the outbound `hysteria` and only accepts `version: 2`. */
    HYSTERIA2(scheme = "hysteria2", xrayProtocol = "hysteria", aliases = setOf("hy2"));

    companion object {
        fun fromScheme(value: String?): ProxyProtocol? = entries.firstOrNull { protocol ->
            protocol.scheme.equals(value, ignoreCase = true) ||
                protocol.aliases.any { alias -> alias.equals(value, ignoreCase = true) }
        }
    }
}

enum class ProxyTransport(val xrayValue: String) {
    RAW("raw"),
    XHTTP("xhttp"),
    GRPC("grpc"),
    WEBSOCKET("websocket"),
    HTTP_UPGRADE("httpupgrade"),
    MKCP("mkcp"),
    HYSTERIA("hysteria"),
    UNKNOWN("unknown");

    companion object {
        fun fromLinkValue(value: String?): ProxyTransport {
            return when (value?.lowercase()) {
                null, "", "tcp", "raw" -> RAW
                "xhttp", "splithttp" -> XHTTP
                "grpc" -> GRPC
                "ws", "websocket" -> WEBSOCKET
                "httpupgrade", "http-upgrade" -> HTTP_UPGRADE
                "kcp", "mkcp" -> MKCP
                // VLESS/VMess/Trojan over the Hysteria transport would need the transport's own auth,
                // which share links don't carry, so they can't connect. Only Hysteria 2 links use HYSTERIA.
                "hysteria" -> UNKNOWN
                else -> UNKNOWN
            }
        }
    }
}

enum class ProxySecurity(val xrayValue: String) {
    NONE("none"),
    TLS("tls"),
    REALITY("reality"),
    UNKNOWN("unknown");

    companion object {
        fun fromLinkValue(value: String?): ProxySecurity {
            return when (value?.lowercase()) {
                null, "", "none" -> NONE
                "tls" -> TLS
                "reality" -> REALITY
                else -> UNKNOWN
            }
        }
    }
}

enum class ProxyProfileSource {
    MANUAL,
    CLIPBOARD,
    REMOTE,
}

enum class ProxyTestStatus {
    NOT_TESTED,
    RUNNING,
    AVAILABLE,
    UNAVAILABLE,
    UNSUPPORTED,
}

data class ProxyImportResult(
    val added: Int,
    val updated: Int,
    val duplicates: Int,
    val invalid: Int,
    val unsupported: Int,
    val total: Int,
    /**
     * Links recognised but not saved because the engine can't run them (Hysteria v1, say). Unlike
     * [unsupported], it leaves out the saved routes whose transport the engine doesn't know.
     */
    val unsupportedSkipped: Int = 0,
) {
    val summary: String
        get() = "Added $added, updated $updated, duplicates $duplicates, invalid $invalid, unsupported $unsupported"
}

data class ProxySyncResult(
    val importResult: ProxyImportResult,
    val notModified: Boolean,
)

data class ProxyTunnelTestResult(
    val profileId: String,
    val status: ProxyTestStatus,
    val latencyMs: Long? = null,
    val message: String? = null,
    val profileFingerprint: String? = null,
)
