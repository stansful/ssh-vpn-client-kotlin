package com.stansful.sshvpnclient.xray

import java.nio.charset.StandardCharsets

/**
 * Authenticated loopback SOCKS endpoint exposed by one live Xray TUN runtime.
 *
 * The endpoint is deliberately caller-provided so its short-lived credentials never need to be
 * persisted. [XrayCoreBridge.createLiveHealthEndpoint] creates a suitable ephemeral instance.
 */
data class XrayLiveHealthEndpoint(
    val port: Int,
    val username: String,
    val password: String,
) {
    init {
        require(port in 1..65_535) { "Live-health SOCKS port is invalid" }
        require(username.utf8Size() in 1..255) {
            "Live-health SOCKS username must be 1..255 UTF-8 bytes"
        }
        require(password.utf8Size() in 1..255) {
            "Live-health SOCKS password must be 1..255 UTF-8 bytes"
        }
    }

    override fun toString(): String = "XrayLiveHealthEndpoint(port=$port, credentials=<redacted>)"
}

/** Binds a loopback endpoint to the exact native runtime generation that owns it. */
@ConsistentCopyVisibility
data class XrayLiveHealthHandle internal constructor(
    val generation: Long,
    val endpoint: XrayLiveHealthEndpoint,
)

data class XrayLiveHealthProbeResult(
    val isHealthy: Boolean,
    val latencyMs: Long,
    val httpStatusCode: Int? = null,
    val message: String? = null,
)

internal fun parseHttpStatusCode(statusLine: String): Int? {
    val firstSpace = statusLine.indexOf(' ')
    if (firstSpace <= 0 || !statusLine.regionMatches(0, "HTTP/", 0, 5)) return null
    val statusStart = firstSpace + 1
    if (statusStart + 3 > statusLine.length) return null
    val statusText = statusLine.substring(statusStart, statusStart + 3)
    if (statusText.any { character -> character !in '0'..'9' }) return null
    if (statusStart + 3 < statusLine.length && statusLine[statusStart + 3] != ' ') return null
    return statusText.toInt().takeIf { status -> status in 100..599 }
}

internal fun isSuccessfulLiveHealthHttpStatus(statusCode: Int): Boolean = statusCode in 200..299

internal fun isLiveHealthHandleCurrent(
    activeOwner: Any?,
    activeGeneration: Long?,
    activeEndpoint: XrayLiveHealthEndpoint?,
    expectedOwner: Any,
    handle: XrayLiveHealthHandle,
): Boolean {
    return activeOwner === expectedOwner &&
        activeGeneration == handle.generation &&
        activeEndpoint == handle.endpoint
}

private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size
