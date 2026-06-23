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

enum class ProxyProtocol(val scheme: String) {
    VLESS("vless"),
    VMESS("vmess"),
    TROJAN("trojan");

    companion object {
        fun fromScheme(value: String?): ProxyProtocol? = entries.firstOrNull {
            it.scheme.equals(value, ignoreCase = true)
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
                "hysteria" -> HYSTERIA
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
)
