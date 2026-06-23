package com.stansful.sshvpnclient.xray

import com.stansful.sshvpnclient.domain.model.ParsedProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyParseResult
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import org.json.JSONArray
import org.json.JSONObject

class XrayConfigBuilder(
    private val parser: ProxyShareLinkParser,
) {
    fun buildTunConfig(profile: ProxyProfile): String {
        val parsed = parse(profile)
        return baseConfig(
            inbound = JSONObject()
                .put("protocol", "tun")
                .put("tag", "tun-in")
                .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500))
                .put(
                    "sniffing",
                    JSONObject()
                        .put("enabled", true)
                        .put("destOverride", JSONArray(listOf("http", "tls", "quic"))),
                ),
            outbound = buildOutbound(parsed),
        ).toString()
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

    private fun parse(profile: ProxyProfile): ParsedProxyProfile {
        return (parser.parse(profile.rawUri) as? ProxyParseResult.Success)?.profile
            ?: error("Stored proxy configuration is invalid")
    }

    private fun baseConfig(inbound: JSONObject, outbound: JSONObject): JSONObject {
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound))
    }

    private fun buildOutbound(profile: ParsedProxyProfile): JSONObject {
        require(profile.transport != ProxyTransport.UNKNOWN) { "Unsupported transport" }
        require(profile.security != ProxySecurity.UNKNOWN) { "Unsupported transport security" }
        return JSONObject()
            .put("protocol", profile.protocol.scheme)
            .put("tag", "proxy-out")
            .put("settings", buildProtocolSettings(profile))
            .put("streamSettings", buildStreamSettings(profile))
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
        }
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
                    ProxyTransport.HYSTERIA -> put("hysteriaSettings", JSONObject())
                    ProxyTransport.UNKNOWN -> Unit
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
                if (parameters["allowinsecure"].toBoolean()) put("allowInsecure", true)
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
}

private fun JSONObject.putIfNotBlank(name: String, value: String?): JSONObject {
    if (!value.isNullOrBlank()) put(name, value)
    return this
}
