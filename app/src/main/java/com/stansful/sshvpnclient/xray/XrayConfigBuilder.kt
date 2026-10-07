package com.stansful.sshvpnclient.xray

import com.stansful.sshvpnclient.domain.model.ParsedProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.usecase.proxy.Hysteria2Parameters
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyParseResult
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import org.json.JSONArray
import org.json.JSONObject

data class XrayBatchSocksTestEntry(
    val profile: ProxyProfile,
    val username: String,
)

class XrayConfigBuilder(
    private val parser: ProxyShareLinkParser,
) {
    fun buildTunConfig(profile: ProxyProfile): String = buildTunConfig(
        profile = profile,
        liveHealthEndpoint = null,
    )

    fun buildTunConfig(
        profile: ProxyProfile,
        liveHealthEndpoint: XrayLiveHealthEndpoint?,
    ): String {
        val parsed = parse(profile)
        val tunInbound = JSONObject()
            .put("protocol", "tun")
            .put("tag", TUN_INBOUND_TAG)
            .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500))
            .put(
                "sniffing",
                JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray(listOf("http", "tls", "quic"))),
            )
        val outbound = buildOutbound(parsed)
        if (liveHealthEndpoint == null) {
            return baseConfig(inbound = tunInbound, outbound = outbound).toString()
        }

        val inbounds = JSONArray()
            .put(tunInbound)
            .put(buildLiveHealthInbound(liveHealthEndpoint))
        return baseConfig(inbounds = inbounds, outbound = outbound)
            .put(
                "routing",
                JSONObject().put(
                    "rules",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "field")
                            .put(
                                "inboundTag",
                                JSONArray().put(TUN_INBOUND_TAG).put(LIVE_HEALTH_INBOUND_TAG),
                            )
                            .put("outboundTag", DEFAULT_OUTBOUND_TAG),
                    ),
                ),
            )
            .toString()
    }

    fun buildSocksTestConfig(profile: ProxyProfile, socksPort: Int): String {
        val parsed = parse(profile)
        return baseConfig(
            inbound = JSONObject()
                .put("listen", "127.0.0.1")
                .put("port", socksPort)
                .put("protocol", "socks")
                .put("tag", "test-in")
                .put("settings", JSONObject().put("udp", true)),
            outbound = buildOutbound(parsed),
        ).toString()
    }

    fun buildBatchSocksTestConfig(
        entries: List<XrayBatchSocksTestEntry>,
        socksPort: Int,
        password: String,
    ): String {
        require(entries.isNotEmpty()) { "At least one test entry is required" }
        require(entries.all { it.username.isNotBlank() }) { "Test usernames must not be blank" }
        require(entries.map(XrayBatchSocksTestEntry::username).distinct().size == entries.size) {
            "Test usernames must be unique"
        }
        require(password.isNotBlank()) { "Test password must not be blank" }

        val inbound = JSONObject()
            .put("listen", "127.0.0.1")
            .put("port", socksPort)
            .put("protocol", "socks")
            .put("tag", BATCH_TEST_INBOUND_TAG)
            .put(
                "settings",
                JSONObject()
                    .put("auth", "password")
                    .put(
                        "accounts",
                        JSONArray(
                            entries.map { entry ->
                                JSONObject()
                                    .put("user", entry.username)
                                    .put("pass", password)
                            },
                        ),
                    )
                    .put("udp", false),
            )
        val outbounds = JSONArray(
            entries.mapIndexed { index, entry ->
                buildOutbound(parse(entry.profile), tag = batchOutboundTag(index))
            },
        )
        val routingRules = JSONArray(
            entries.mapIndexed { index, entry ->
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put(BATCH_TEST_INBOUND_TAG))
                    .put("user", JSONArray().put(entry.username))
                    .put("outboundTag", batchOutboundTag(index))
            },
        )

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("rules", routingRules))
            .toString()
    }

    private fun parse(profile: ProxyProfile): ParsedProxyProfile {
        return (parser.parse(profile.rawUri) as? ProxyParseResult.Success)?.profile
            ?: error("Stored proxy configuration is invalid")
    }

    private fun buildLiveHealthInbound(endpoint: XrayLiveHealthEndpoint): JSONObject {
        return JSONObject()
            .put("listen", LIVE_HEALTH_LOOPBACK_HOST)
            .put("port", endpoint.port)
            .put("protocol", "socks")
            .put("tag", LIVE_HEALTH_INBOUND_TAG)
            .put(
                "settings",
                JSONObject()
                    .put("auth", "password")
                    .put(
                        "accounts",
                        JSONArray().put(
                            JSONObject()
                                .put("user", endpoint.username)
                                .put("pass", endpoint.password),
                        ),
                    )
                    .put("udp", false),
            )
    }

    private fun baseConfig(inbound: JSONObject, outbound: JSONObject): JSONObject {
        return baseConfig(JSONArray().put(inbound), outbound)
    }

    private fun baseConfig(inbounds: JSONArray, outbound: JSONObject): JSONObject {
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", inbounds)
            .put("outbounds", JSONArray().put(outbound))
    }

    private fun buildOutbound(
        profile: ParsedProxyProfile,
        tag: String = DEFAULT_OUTBOUND_TAG,
    ): JSONObject {
        require(profile.transport != ProxyTransport.UNKNOWN) { "Unsupported transport" }
        require(profile.security != ProxySecurity.UNKNOWN) { "Unsupported transport security" }
        val streamSettings = if (profile.protocol == ProxyProtocol.HYSTERIA2) {
            buildHysteria2StreamSettings(profile)
        } else {
            buildStreamSettings(profile)
        }
        return JSONObject()
            .put("protocol", profile.protocol.xrayProtocol)
            .put("tag", tag)
            .put("settings", buildProtocolSettings(profile))
            .put("streamSettings", streamSettings)
    }

    private fun buildProtocolSettings(profile: ParsedProxyProfile): JSONObject {
        return when (profile.protocol) {
            ProxyProtocol.VLESS -> JSONObject()
                .put("address", profile.host)
                .put("port", profile.port)
                .put("id", profile.credential)
                .put("encryption", profile.parameters["encryption"] ?: "none")
                .putIfNotBlank("flow", profile.flow)
                .put("level", 0)
            ProxyProtocol.VMESS -> JSONObject()
                .put("address", profile.host)
                .put("port", profile.port)
                .put("id", profile.credential)
                .put("security", profile.parameters["scy"] ?: "auto")
                .put("level", 0)
            ProxyProtocol.TROJAN -> JSONObject()
                .put("address", profile.host)
                .put("port", profile.port)
                .put("password", profile.credential)
                .put("level", 0)
            ProxyProtocol.HYSTERIA2 -> JSONObject()
                .put("version", HYSTERIA_VERSION)
                .put("address", profile.host)
                .put("port", profile.port)
        }
    }

    /**
     * Hysteria 2 is always QUIC with TLS. The parser has already normalized [Hysteria2Parameters], so
     * every value here is one Xray-core v26 accepts: an unknown congestion name, for instance, would
     * make the native dialer panic and take the whole app process down.
     */
    private fun buildHysteria2StreamSettings(profile: ParsedProxyProfile): JSONObject {
        val parameters = profile.parameters
        // The hysteria dialer builds its TLS config without the destination, so with no explicit
        // serverName quic-go would send and verify the placeholder host "hysteria".
        // allowInsecure is not emitted on purpose (see buildTlsSettings); a certificate pin is the
        // only way Xray-core accepts a self-signed server certificate.
        val tlsSettings = JSONObject()
            .put("serverName", parameters[Hysteria2Parameters.SNI] ?: profile.host.removeSurrounding("[", "]"))
            .putIfNotBlank("pinnedPeerCertSha256", parameters[Hysteria2Parameters.PIN_SHA256])
            .putIfNotBlank("echConfigList", parameters[Hysteria2Parameters.ECH])
        return JSONObject()
            .put("network", ProxyTransport.HYSTERIA.xrayValue)
            .put("security", ProxySecurity.TLS.xrayValue)
            .put("tlsSettings", tlsSettings)
            .put(
                "hysteriaSettings",
                JSONObject().put("version", HYSTERIA_VERSION).put("auth", profile.credential),
            )
            .put("finalmask", buildHysteria2FinalMask(parameters))
    }

    /** Salamander/Gecko obfuscation, QUIC keep-alive, Brutal bandwidth and port hopping live in Xray's `finalmask`. */
    private fun buildHysteria2FinalMask(parameters: Map<String, String>): JSONObject {
        val salamander = parameters[Hysteria2Parameters.OBFS_PASSWORD]?.let { password ->
            val settings = JSONObject().put("password", password)
            // Xray runs Gecko as Salamander with a packet size range; the official client's URI has no
            // sizes, so its defaults are spelled out (they also match Xray's own).
            if (parameters[Hysteria2Parameters.OBFS] == Hysteria2Parameters.GECKO) {
                settings.put("packetSize", GECKO_DEFAULT_PACKET_SIZE)
            }
            JSONObject()
                .put("type", Hysteria2Parameters.SALAMANDER)
                .put("settings", settings)
        }
        val hopPorts = parameters[Hysteria2Parameters.PORTS]
        // Xray sends no QUIC keep-alive by default, so an idle tunnel (a chat app's push connection with
        // the screen off) would hit the 30 s idle timeout and reset every stream. The official client pings
        // every 10 s. No explicit congestion: Xray then uses Brutal when both sides announce bandwidth and
        // BBR otherwise, which is also what the official client does.
        val quicParams = JSONObject()
            .put("keepAlivePeriod", HYSTERIA_KEEP_ALIVE_SECONDS)
            .putIfNotBlank("brutalUp", parameters[Hysteria2Parameters.UP])
            .putIfNotBlank("brutalDown", parameters[Hysteria2Parameters.DOWN])
            .apply {
                if (hopPorts != null) {
                    put(
                        "udpHop",
                        JSONObject()
                            .put("ports", hopPorts)
                            .putIfNotBlank("interval", parameters[Hysteria2Parameters.HOP_INTERVAL]),
                    )
                }
            }
        return JSONObject()
            .apply { if (salamander != null) put("udp", JSONArray().put(salamander)) }
            .put("quicParams", quicParams)
    }

    private fun buildStreamSettings(profile: ParsedProxyProfile): JSONObject {
        val parameters = profile.parameters
        return JSONObject()
            .put("network", profile.transport.xrayValue)
            .put("security", profile.security.xrayValue)
            .apply {
                when (profile.transport) {
                    ProxyTransport.RAW -> put(
                        "rawSettings",
                        JSONObject().put(
                            "header",
                            JSONObject().put("type", parameters["headertype"] ?: "none"),
                        ),
                    )
                    ProxyTransport.XHTTP -> put("xhttpSettings", buildXhttpSettings(parameters))
                    ProxyTransport.GRPC -> put(
                        "grpcSettings",
                        JSONObject()
                            .put("serviceName", parameters["servicename"].orEmpty())
                            .put("multiMode", parameters["mode"] == "multi"),
                    )
                    ProxyTransport.WEBSOCKET -> put(
                        "wsSettings",
                        JSONObject()
                            .put("path", parameters["path"] ?: "/")
                            .put(
                                "headers",
                                JSONObject().putIfNotBlank("Host", parameters["host"]),
                            ),
                    )
                    ProxyTransport.HTTP_UPGRADE -> put(
                        "httpupgradeSettings",
                        JSONObject()
                            .put("path", parameters["path"] ?: "/")
                            .putIfNotBlank("host", parameters["host"]),
                    )
                    ProxyTransport.MKCP -> put(
                        "kcpSettings",
                        JSONObject().put(
                            "header",
                            JSONObject().put("type", parameters["headertype"] ?: "none"),
                        ),
                    )
                    // Only Hysteria 2 profiles carry this transport and they have their own stream settings.
                    ProxyTransport.HYSTERIA,
                    ProxyTransport.UNKNOWN,
                    -> Unit
                }
                when (profile.security) {
                    ProxySecurity.TLS -> put("tlsSettings", buildTlsSettings(parameters))
                    ProxySecurity.REALITY -> put("realitySettings", buildRealitySettings(parameters))
                    ProxySecurity.NONE,
                    ProxySecurity.UNKNOWN,
                    -> Unit
                }
            }
    }

    private fun buildTlsSettings(parameters: Map<String, String>): JSONObject {
        return JSONObject()
            .putIfNotBlank("serverName", parameters["sni"] ?: parameters["host"])
            .putIfNotBlank("fingerprint", parameters["fp"])
            .apply {
                parameters["alpn"]?.split(',')?.filter(String::isNotBlank)?.let { values ->
                    put("alpn", JSONArray(values))
                }
                // `allowinsecure` is deliberately ignored: Xray-core v26 refuses to build any config
                // containing "allowInsecure" after 2026-06-01, so emitting it broke the whole route.
                // Without it the certificate is verified normally.
            }
    }

    private fun buildRealitySettings(parameters: Map<String, String>): JSONObject {
        return JSONObject()
            .putIfNotBlank("serverName", parameters["sni"])
            .putIfNotBlank("fingerprint", parameters["fp"])
            .putIfNotBlank("publicKey", parameters["pbk"])
            .putIfNotBlank("shortId", parameters["sid"])
            .putIfNotBlank("spiderX", parameters["spx"])
    }

    private fun buildXhttpSettings(parameters: Map<String, String>): JSONObject {
        val fromExtra = parameters["extra"]?.let { value ->
            runCatching { JSONObject(value) }.getOrNull()
        } ?: JSONObject()
        return fromExtra
            .putIfNotBlank("host", parameters["host"])
            .put("path", parameters["path"] ?: fromExtra.optString("path", "/"))
            .putIfNotBlank("mode", parameters["mode"])
    }

    private fun batchOutboundTag(index: Int): String = "probe-out-$index"

    private companion object {
        const val BATCH_TEST_INBOUND_TAG = "batch-test-in"
        const val TUN_INBOUND_TAG = "tun-in"
        const val LIVE_HEALTH_INBOUND_TAG = "live-health-in"
        const val LIVE_HEALTH_LOOPBACK_HOST = "127.0.0.1"
        const val DEFAULT_OUTBOUND_TAG = "proxy-out"

        /** Xray-core only implements Hysteria 2 and rejects any other version. */
        const val HYSTERIA_VERSION = 2

        /** The official client's default; Xray accepts 2..60. */
        const val HYSTERIA_KEEP_ALIVE_SECONDS = 10

        /** Gecko's default packet sizes in the official Hysteria client. */
        const val GECKO_DEFAULT_PACKET_SIZE = "512-1200"
    }
}

private fun JSONObject.putIfNotBlank(name: String, value: String?): JSONObject {
    if (!value.isNullOrBlank()) put(name, value)
    return this
}
