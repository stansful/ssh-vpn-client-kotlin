package com.stansful.sshvpnclient.domain.usecase.proxy

import com.stansful.sshvpnclient.domain.model.ParsedProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject

sealed interface ProxyParseResult {
    data class Success(val profile: ParsedProxyProfile) : ProxyParseResult

    /**
     * [unsupported] marks a well-known link the engine cannot run (e.g. Hysteria v1), so imports
     * count it as unsupported rather than invalid. Such links are never saved.
     */
    data class Failure(val reason: String, val unsupported: Boolean = false) : ProxyParseResult
}

/** Failure reasons the Add sheet maps to its own messages (RouteLinkAnalysis.problemOf). */
object ProxyParseReasons {
    const val HOST_MISSING = "Host is missing"
    const val PORT_INVALID = "Port is invalid"
    const val HYSTERIA_V1_UNSUPPORTED = "Hysteria v1 is not supported"
    const val OBFS_UNSUPPORTED = "Obfuscation is not supported"
    const val OBFS_PASSWORD_MISSING = "Obfuscation password is missing"
    const val OBFS_PASSWORD_TOO_SHORT = "Obfuscation password is too short"
    const val PIN_INVALID = "Certificate pin is invalid"
    const val ECH_INVALID = "ECH config is invalid"
}

class ProxyShareLinkParser {
    private val hysteria2Parser = Hysteria2LinkParser()

    fun parse(rawValue: String): ProxyParseResult {
        val value = rawValue.trim()
        if (value.isEmpty()) return ProxyParseResult.Failure("Empty configuration")
        if (value.length > MAX_LINK_LENGTH) return ProxyParseResult.Failure("Configuration is too large")

        return runCatching {
            when (value.substringBefore(':').lowercase()) {
                ProxyProtocol.VLESS.scheme -> parseStandardUri(value, ProxyProtocol.VLESS)
                ProxyProtocol.TROJAN.scheme -> parseStandardUri(value, ProxyProtocol.TROJAN)
                ProxyProtocol.VMESS.scheme -> parseVmess(value)
                ProxyProtocol.HYSTERIA2.scheme,
                in ProxyProtocol.HYSTERIA2.aliases,
                -> hysteria2Parser.parse(value)
                HYSTERIA_V1_SCHEME -> ProxyParseResult.Failure(
                    ProxyParseReasons.HYSTERIA_V1_UNSUPPORTED,
                    unsupported = true,
                )
                else -> ProxyParseResult.Failure("Unsupported protocol")
            }
        }.getOrElse { error ->
            ProxyParseResult.Failure(error.message ?: "Invalid configuration")
        }
    }

    fun parseMany(text: String): List<ProxyParseResult> {
        return text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .take(MAX_IMPORT_LINES + 1)
            .map(::parse)
            .toList()
            .let { results ->
                if (results.size <= MAX_IMPORT_LINES) results
                else results.take(MAX_IMPORT_LINES) + ProxyParseResult.Failure("Import limit exceeded")
            }
    }

    private fun parseStandardUri(value: String, protocol: ProxyProtocol): ProxyParseResult {
        val uri = URI(value)
        val host = uri.host?.trim()?.takeIf(String::isNotEmpty)
            ?: return ProxyParseResult.Failure(ProxyParseReasons.HOST_MISSING)
        val port = uri.port.takeIf { it in 1..65_535 }
            ?: return ProxyParseResult.Failure(ProxyParseReasons.PORT_INVALID)
        val credential = uri.rawUserInfo?.let(::decodeShareLinkComponent)?.takeIf(String::isNotBlank)
            ?: return ProxyParseResult.Failure(
                if (protocol == ProxyProtocol.TROJAN) "Password is missing" else "UUID is missing",
            )
        val parameters = parseShareLinkQuery(uri.rawQuery)
        val transport = ProxyTransport.fromLinkValue(parameters["type"] ?: parameters["network"])
        val security = ProxySecurity.fromLinkValue(parameters["security"])
        val name = uri.rawFragment?.let(::decodeShareLinkComponent)?.trim().orEmpty().ifBlank {
            "${protocol.name} $host:$port"
        }
        val canonical = buildString {
            append(protocol.scheme).append("://")
            append(credential).append('@').append(host.lowercase()).append(':').append(port)
            parameters.toSortedMap().forEach { (key, parameterValue) ->
                append('|').append(key.lowercase()).append('=').append(parameterValue)
            }
        }
        return ProxyParseResult.Success(
            ParsedProxyProfile(
                name = name,
                protocol = protocol,
                host = host,
                port = port,
                transport = transport,
                security = security,
                flow = parameters["flow"],
                credential = credential,
                rawUri = value,
                fingerprint = sha256Hex(canonical),
                parameters = parameters,
            ),
        )
    }

    private fun parseVmess(value: String): ProxyParseResult {
        val encoded = value.removePrefix("vmess://").trim()
        val decoded = decodeBase64(encoded)
        val json = JSONObject(decoded)
        val host = json.optString("add").trim().takeIf(String::isNotEmpty)
            ?: return ProxyParseResult.Failure(ProxyParseReasons.HOST_MISSING)
        val port = json.optString("port").toIntOrNull()?.takeIf { it in 1..65_535 }
            ?: return ProxyParseResult.Failure(ProxyParseReasons.PORT_INVALID)
        val id = json.optString("id").trim().takeIf(String::isNotEmpty)
            ?: return ProxyParseResult.Failure("UUID is missing")
        val parameters = buildMap {
            VMESS_FIELDS.forEach { key ->
                json.optString(key).takeIf(String::isNotBlank)?.let { put(key.lowercase(), it) }
            }
        }
        val transport = ProxyTransport.fromLinkValue(parameters["net"] ?: parameters["type"])
        val security = ProxySecurity.fromLinkValue(parameters["tls"])
        val name = json.optString("ps").trim().ifBlank { "VMESS $host:$port" }
        val canonical = buildString {
            append("vmess://").append(id).append('@').append(host.lowercase()).append(':').append(port)
            parameters.filterKeys { it != "ps" }.toSortedMap().forEach { (key, parameterValue) ->
                append('|').append(key.lowercase()).append('=').append(parameterValue)
            }
        }
        return ProxyParseResult.Success(
            ParsedProxyProfile(
                name = name,
                protocol = ProxyProtocol.VMESS,
                host = host,
                port = port,
                transport = transport,
                security = security,
                flow = null,
                credential = id,
                rawUri = value,
                fingerprint = sha256Hex(canonical),
                parameters = parameters,
            ),
        )
    }

    private fun decodeBase64(value: String): String {
        val normalized = value.replace("\n", "").replace("\r", "")
        val padding = "=".repeat((4 - normalized.length % 4) % 4)
        val bytes = runCatching { Base64.getUrlDecoder().decode(normalized + padding) }
            .recoverCatching { Base64.getDecoder().decode(normalized + padding) }
            .getOrThrow()
        return String(bytes, StandardCharsets.UTF_8)
    }

    private companion object {
        const val HYSTERIA_V1_SCHEME = "hysteria"
        const val MAX_LINK_LENGTH = 64 * 1_024
        const val MAX_IMPORT_LINES = 10_000
        val VMESS_FIELDS = setOf(
            "aid",
            "alpn",
            "fp",
            "host",
            "net",
            "path",
            "scy",
            "security",
            "sni",
            "tls",
            "type",
        )
    }
}

/**
 * Splits `a=1&b=2` into lowercase keys and decoded values, trimmed unless [trimValues] is false; a repeated
 * key keeps the last value.
 */
internal fun parseShareLinkQuery(rawQuery: String?, trimValues: Boolean = true): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return rawQuery.split('&')
        .asSequence()
        .filter(String::isNotBlank)
        .map { part ->
            val key = decodeShareLinkComponent(part.substringBefore('=')).trim().lowercase()
            val decoded = decodeShareLinkComponent(part.substringAfter('=', ""))
            val value = if (trimValues) decoded.trim() else decoded
            key to value
        }
        .filter { (key, _) -> key.isNotEmpty() }
        .toMap()
}

internal fun decodeShareLinkComponent(value: String): String {
    return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

internal fun sha256Hex(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
