package com.stansful.sshvpnclient.xray

import com.stansful.sshvpnclient.domain.model.ProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyParseResult
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class XrayConfigBuilderTest {
    private val builder = XrayConfigBuilder(ProxyShareLinkParser())

    @Test
    fun `builds tun config with reality outbound`() {
        val config = JSONObject(builder.buildTunConfig(profile()))
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)

        assertEquals(1, config.getJSONArray("inbounds").length())
        assertEquals(false, config.has("routing"))
        assertEquals("tun", inbound.getString("protocol"))
        assertEquals("vless", outbound.getString("protocol"))
        assertEquals("reality", outbound.getJSONObject("streamSettings").getString("security"))
        assertEquals(
            "public-key",
            outbound.getJSONObject("streamSettings")
                .getJSONObject("realitySettings")
                .getString("publicKey"),
        )
    }

    @Test
    fun `live tun health endpoint is authenticated loopback and shares proxy outbound`() {
        val config = JSONObject(
            builder.buildTunConfig(
                profile = profile(),
                liveHealthEndpoint = XrayLiveHealthEndpoint(
                    port = 12_345,
                    username = "health-user",
                    password = "health-password",
                ),
            ),
        )

        val inbounds = config.getJSONArray("inbounds")
        val tunInbound = inbounds.getJSONObject(0)
        val healthInbound = inbounds.getJSONObject(1)
        val healthSettings = healthInbound.getJSONObject("settings")
        val account = healthSettings.getJSONArray("accounts").getJSONObject(0)
        val outbounds = config.getJSONArray("outbounds")
        val route = config.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        val routedInbounds = route.getJSONArray("inboundTag")

        assertEquals(2, inbounds.length())
        assertEquals("tun-in", tunInbound.getString("tag"))
        assertEquals("127.0.0.1", healthInbound.getString("listen"))
        assertEquals(12_345, healthInbound.getInt("port"))
        assertEquals("socks", healthInbound.getString("protocol"))
        assertEquals("live-health-in", healthInbound.getString("tag"))
        assertEquals("password", healthSettings.getString("auth"))
        assertEquals(false, healthSettings.getBoolean("udp"))
        assertEquals("health-user", account.getString("user"))
        assertEquals("health-password", account.getString("pass"))
        assertEquals(1, outbounds.length())
        assertEquals("proxy-out", outbounds.getJSONObject(0).getString("tag"))
        assertEquals("tun-in", routedInbounds.getString(0))
        assertEquals("live-health-in", routedInbounds.getString(1))
        assertEquals("proxy-out", route.getString("outboundTag"))
    }

    @Test
    fun `builds authenticated batch socks config with user-specific routes`() {
        val config = JSONObject(
            builder.buildBatchSocksTestConfig(
                entries = listOf(
                    XrayBatchSocksTestEntry(profile = profile(), username = "probe-user-0"),
                    XrayBatchSocksTestEntry(
                        profile = profile().copy(
                            id = "profile-2",
                            rawUri = "vless://id-2@example.net:8443?security=tls&type=ws&path=%2Fws",
                        ),
                        username = "probe-user-1",
                    ),
                ),
                socksPort = 10_880,
                password = "shared-secret",
            ),
        )

        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        val settings = inbound.getJSONObject("settings")
        val accounts = settings.getJSONArray("accounts")
        val outbounds = config.getJSONArray("outbounds")
        val rules = config.getJSONObject("routing").getJSONArray("rules")

        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals(10_880, inbound.getInt("port"))
        assertEquals("socks", inbound.getString("protocol"))
        assertEquals("batch-test-in", inbound.getString("tag"))
        assertEquals("password", settings.getString("auth"))
        assertEquals(false, settings.getBoolean("udp"))
        assertEquals(2, accounts.length())
        assertEquals("probe-user-0", accounts.getJSONObject(0).getString("user"))
        assertEquals("shared-secret", accounts.getJSONObject(0).getString("pass"))
        assertEquals("probe-user-1", accounts.getJSONObject(1).getString("user"))
        assertEquals("shared-secret", accounts.getJSONObject(1).getString("pass"))
        assertEquals("probe-out-0", outbounds.getJSONObject(0).getString("tag"))
        assertEquals("probe-out-1", outbounds.getJSONObject(1).getString("tag"))
        assertEquals("probe-user-0", rules.getJSONObject(0).getJSONArray("user").getString(0))
        assertEquals("probe-out-0", rules.getJSONObject(0).getString("outboundTag"))
        assertEquals("probe-user-1", rules.getJSONObject(1).getJSONArray("user").getString(0))
        assertEquals("probe-out-1", rules.getJSONObject(1).getString("outboundTag"))
        assertEquals(
            "batch-test-in",
            rules.getJSONObject(1).getJSONArray("inboundTag").getString(0),
        )
    }

    @Test
    fun `batch config retains all 500 independently routed profiles`() {
        val entries = (0 until 500).map { index ->
            XrayBatchSocksTestEntry(
                profile = profile().copy(
                    id = "profile-$index",
                    name = "Profile $index",
                    fingerprint = "fingerprint-$index",
                ),
                username = "probe-$index",
            )
        }

        val config = JSONObject(
            builder.buildBatchSocksTestConfig(
                entries = entries,
                socksPort = 10_880,
                password = "shared-secret",
            ),
        )
        val accounts = config.getJSONArray("inbounds")
            .getJSONObject(0)
            .getJSONObject("settings")
            .getJSONArray("accounts")
        val outbounds = config.getJSONArray("outbounds")
        val rules = config.getJSONObject("routing").getJSONArray("rules")

        assertEquals(500, accounts.length())
        assertEquals(500, outbounds.length())
        assertEquals(500, rules.length())
        assertEquals("probe-499", accounts.getJSONObject(499).getString("user"))
        assertEquals("probe-out-499", outbounds.getJSONObject(499).getString("tag"))
        assertEquals("probe-499", rules.getJSONObject(499).getJSONArray("user").getString(0))
        assertEquals("probe-out-499", rules.getJSONObject(499).getString("outboundTag"))
    }

    @Test
    fun `builds hysteria 2 outbound for a plain link`() {
        val outbound = outboundOf("hy2://secret@example.com:8443?sni=sni.example#Plain")
        val settings = outbound.getJSONObject("settings")
        val stream = outbound.getJSONObject("streamSettings")
        val tls = stream.getJSONObject("tlsSettings")
        val hysteria = stream.getJSONObject("hysteriaSettings")

        assertEquals("hysteria", outbound.getString("protocol"))
        assertEquals("proxy-out", outbound.getString("tag"))
        assertEquals(setOf("version", "address", "port"), settings.keyNames())
        assertEquals(2, settings.getInt("version"))
        assertEquals("example.com", settings.getString("address"))
        assertEquals(8443, settings.getInt("port"))
        assertEquals(setOf("network", "security", "tlsSettings", "hysteriaSettings", "finalmask"), stream.keyNames())
        assertEquals("hysteria", stream.getString("network"))
        assertEquals("tls", stream.getString("security"))
        assertEquals(setOf("serverName"), tls.keyNames())
        assertEquals("sni.example", tls.getString("serverName"))
        assertEquals(setOf("version", "auth"), hysteria.keyNames())
        assertEquals(2, hysteria.getInt("version"))
        assertEquals("secret", hysteria.getString("auth"))
        assertEquals(setOf("quicParams"), stream.getJSONObject("finalmask").keyNames())
    }

    @Test
    fun `every hysteria 2 outbound keeps idle quic connections alive like the official client`() {
        listOf(
            "hy2://secret@example.com",
            "hy2://secret@example.com?obfs=salamander&obfs-password=obfs-pw&up=50&mport=20000-50000",
        ).forEach { link ->
            val quicParams = finalMaskOf(link).getJSONObject("quicParams")

            assertEquals(link, 10, quicParams.getInt("keepAlivePeriod"))
            assertFalse(link, quicParams.has("congestion"))
        }
    }

    @Test
    fun `hysteria 2 server name falls back to the host without ipv6 brackets`() {
        val named = outboundOf("hy2://secret@example.com")
        val ipv6 = outboundOf("hy2://secret@[2001:db8::1]:8443")

        assertEquals("example.com", tlsOf(named).getString("serverName"))
        assertEquals(443, named.getJSONObject("settings").getInt("port"))
        assertEquals("2001:db8::1", tlsOf(ipv6).getString("serverName"))
        assertEquals("[2001:db8::1]", ipv6.getJSONObject("settings").getString("address"))
        assertEquals(8443, ipv6.getJSONObject("settings").getInt("port"))
        assertEquals(
            "sni.example",
            tlsOf(outboundOf("hy2://secret@[2001:db8::1]?peer=sni.example")).getString("serverName"),
        )
    }

    @Test
    fun `hysteria 2 passes the certificate pin but never allowInsecure alpn or fingerprint`() {
        val tls = tlsOf(
            outboundOf("hy2://secret@example.com?sni=sni.example&pinSHA256=$COLON_PIN&insecure=1&alpn=h3&fp=chrome"),
        )

        assertEquals(setOf("serverName", "pinnedPeerCertSha256"), tls.keyNames())
        assertEquals("sni.example", tls.getString("serverName"))
        assertEquals(PIN, tls.getString("pinnedPeerCertSha256"))
    }

    @Test
    fun `insecure hysteria 2 link without a valid pin keeps certificate verification`() {
        listOf("insecure=1", "allowInsecure=true").forEach { query ->
            val tls = tlsOf(outboundOf("hy2://secret@example.com?$query"))

            assertEquals(query, setOf("serverName"), tls.keyNames())
            assertEquals(query, "example.com", tls.getString("serverName"))
        }
    }

    @Test
    fun `hysteria 2 auth is the whole decoded credential`() {
        assertEquals("user:pa ss", hysteriaSettingsOf("hy2://user:pa%20ss@example.com").getString("auth"))
        assertEquals("query-secret", hysteriaSettingsOf("hy2://example.com?auth=query-secret").getString("auth"))
    }

    @Test
    fun `salamander obfuscation becomes the only udp mask`() {
        val finalMask = finalMaskOf("hy2://secret@example.com?obfs=salamander&obfs-password=obfs-pw")
        val masks = finalMask.getJSONArray("udp")
        val mask = masks.getJSONObject(0)

        assertEquals(setOf("udp", "quicParams"), finalMask.keyNames())
        assertEquals(1, masks.length())
        assertEquals("salamander", mask.getString("type"))
        assertEquals("obfs-pw", mask.getJSONObject("settings").getString("password"))
        assertEquals(setOf("password"), mask.getJSONObject("settings").keyNames())
    }

    @Test
    fun `gecko obfuscation is salamander with the official default packet sizes`() {
        val mask = finalMaskOf("hy2://secret@example.com?obfs=gecko&obfs-password=obfs-pw")
            .getJSONArray("udp")
            .getJSONObject(0)
        val settings = mask.getJSONObject("settings")

        assertEquals("salamander", mask.getString("type"))
        assertEquals("obfs-pw", settings.getString("password"))
        assertEquals("512-1200", settings.getString("packetSize"))
    }

    @Test
    fun `hysteria 2 passes the ech config list to tls`() {
        val tls = tlsOf(outboundOf("hy2://secret@example.com?ech=AAEC_w"))

        assertEquals(setOf("serverName", "echConfigList"), tls.keyNames())
        assertEquals("AAEC/w==", tls.getString("echConfigList"))
    }

    @Test
    fun `brutal bandwidth is emitted only for the directions the link sets`() {
        val upOnly = finalMaskOf("hy2://secret@example.com?up=100")
        val downOnly = finalMaskOf("hy2://secret@example.com?downmbps=200").getJSONObject("quicParams")
        val both = finalMaskOf("hy2://secret@example.com?up=50&down=1%20gbps").getJSONObject("quicParams")

        assertEquals(setOf("quicParams"), upOnly.keyNames())
        assertEquals(setOf("keepAlivePeriod", "brutalUp"), upOnly.getJSONObject("quicParams").keyNames())
        assertEquals("100 mbps", upOnly.getJSONObject("quicParams").getString("brutalUp"))
        assertEquals(setOf("keepAlivePeriod", "brutalDown"), downOnly.keyNames())
        assertEquals("200 mbps", downOnly.getString("brutalDown"))
        assertEquals(setOf("keepAlivePeriod", "brutalUp", "brutalDown"), both.keyNames())
        assertEquals("50 mbps", both.getString("brutalUp"))
        assertEquals("1 gbps", both.getString("brutalDown"))
    }

    @Test
    fun `bandwidth xray would reject is left out`() {
        assertEquals(
            setOf("keepAlivePeriod"),
            finalMaskOf("hy2://secret@example.com?up=1%20kbps&down=fast").getJSONObject("quicParams").keyNames(),
        )
    }

    @Test
    fun `port hopping becomes a udp hop with an optional interval`() {
        val outbound = outboundOf("hy2://secret@example.com:443,20000-50000?hop-interval=10-30")
        val quicParams = outbound.getJSONObject("streamSettings")
            .getJSONObject("finalmask")
            .getJSONObject("quicParams")
        val hop = quicParams.getJSONObject("udpHop")
        val withoutInterval = finalMaskOf("hy2://secret@example.com?mport=20000:50000")
            .getJSONObject("quicParams")
            .getJSONObject("udpHop")

        assertEquals(443, outbound.getJSONObject("settings").getInt("port"))
        assertEquals(setOf("keepAlivePeriod", "udpHop"), quicParams.keyNames())
        assertEquals(setOf("ports", "interval"), hop.keyNames())
        assertEquals("443,20000-50000", hop.getString("ports"))
        assertEquals("10-30", hop.getString("interval"))
        assertEquals(setOf("ports"), withoutInterval.keyNames())
        assertEquals("20000-50000", withoutInterval.getString("ports"))
    }

    @Test
    fun `obfuscation brutal bandwidth and port hopping share one finalmask`() {
        val finalMask = finalMaskOf(
            "hy2://secret@example.com:8443?sni=sni.example&obfs=salamander&obfs-password=obfs-pw" +
                "&mport=20000-50000&hop-interval=30s&up=50&down=100%20mbps",
        )
        val quicParams = finalMask.getJSONObject("quicParams")
        val hop = quicParams.getJSONObject("udpHop")

        assertEquals(setOf("udp", "quicParams"), finalMask.keyNames())
        assertEquals("salamander", finalMask.getJSONArray("udp").getJSONObject(0).getString("type"))
        assertEquals(setOf("keepAlivePeriod", "brutalUp", "brutalDown", "udpHop"), quicParams.keyNames())
        assertEquals("50 mbps", quicParams.getString("brutalUp"))
        assertEquals("100 mbps", quicParams.getString("brutalDown"))
        assertEquals(setOf("ports", "interval"), hop.keyNames())
        assertEquals("20000-50000", hop.getString("ports"))
        assertEquals("30", hop.getString("interval"))
    }

    @Test
    fun `batch config mixes vless and hysteria 2 outbounds`() {
        val hysteria2 = profile().copy(
            id = "hysteria2",
            protocol = ProxyProtocol.HYSTERIA2,
            host = "example.net",
            transport = ProxyTransport.HYSTERIA,
            security = ProxySecurity.TLS,
            flow = null,
            rawUri = "hy2://secret@example.net:443,20000-50000?sni=sni.example" +
                "&obfs=salamander&obfs-password=obfs-pw",
        )
        val config = JSONObject(
            builder.buildBatchSocksTestConfig(
                entries = listOf(
                    XrayBatchSocksTestEntry(profile = profile(), username = "probe-vless"),
                    XrayBatchSocksTestEntry(profile = hysteria2, username = "probe-hysteria2"),
                ),
                socksPort = 10_880,
                password = "shared-secret",
            ),
        )
        val outbounds = config.getJSONArray("outbounds")
        val vless = outbounds.getJSONObject(0)
        val hysteria = outbounds.getJSONObject(1)
        val hysteriaStream = hysteria.getJSONObject("streamSettings")
        val hop = hysteriaStream.getJSONObject("finalmask").getJSONObject("quicParams").getJSONObject("udpHop")
        val rule = config.getJSONObject("routing").getJSONArray("rules").getJSONObject(1)

        assertEquals(2, outbounds.length())
        assertEquals("vless", vless.getString("protocol"))
        assertEquals("probe-out-0", vless.getString("tag"))
        assertEquals("reality", vless.getJSONObject("streamSettings").getString("security"))
        assertEquals("hysteria", hysteria.getString("protocol"))
        assertEquals("probe-out-1", hysteria.getString("tag"))
        assertEquals("example.net", hysteria.getJSONObject("settings").getString("address"))
        assertEquals("hysteria", hysteriaStream.getString("network"))
        assertEquals("sni.example", hysteriaStream.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("secret", hysteriaStream.getJSONObject("hysteriaSettings").getString("auth"))
        assertEquals("443,20000-50000", hop.getString("ports"))
        assertEquals("probe-hysteria2", rule.getJSONArray("user").getString(0))
        assertEquals("probe-out-1", rule.getString("outboundTag"))
    }

    @Test
    fun `tun config routes through the hysteria 2 outbound`() {
        val config = JSONObject(
            builder.buildTunConfig(profile().copy(rawUri = "hy2://secret@example.com?sni=sni.example")),
        )
        val outbounds = config.getJSONArray("outbounds")
        val outbound = outbounds.getJSONObject(0)

        assertEquals("tun", config.getJSONArray("inbounds").getJSONObject(0).getString("protocol"))
        assertEquals(1, outbounds.length())
        assertEquals("hysteria", outbound.getString("protocol"))
        assertEquals("proxy-out", outbound.getString("tag"))
        assertEquals("hysteria", outbound.getJSONObject("streamSettings").getString("network"))
        assertEquals("sni.example", tlsOf(outbound).getString("serverName"))
    }

    @Test
    fun `vless allowInsecure is no longer passed to xray`() {
        listOf("allowInsecure=true", "allowInsecure=1", "allowinsecure=true").forEach { flag ->
            val tls = tlsOf(
                outboundOf(
                    "vless://id@example.com:443?security=tls&type=tcp&sni=example.org&fp=chrome" +
                        "&alpn=h2,http/1.1&$flag",
                ),
            )

            assertFalse(flag, tls.has("allowInsecure"))
            assertEquals(flag, "example.org", tls.getString("serverName"))
            assertEquals(flag, "chrome", tls.getString("fingerprint"))
            assertEquals(flag, "http/1.1", tls.getJSONArray("alpn").getString(1))
        }
    }

    @Test
    fun `vless over the hysteria transport is unsupported because links can't carry its auth`() {
        listOf(
            "vless://id@example.com:443?type=hysteria&security=tls&sni=example.org",
            "trojan://pw@example.com:443?type=hysteria&security=tls",
        ).forEach { link ->
            val parsed = ProxyShareLinkParser().parse(link) as ProxyParseResult.Success

            assertEquals(link, ProxyTransport.UNKNOWN, parsed.profile.transport)
            assertThrows(link, IllegalArgumentException::class.java) { outboundOf(link) }
        }
    }

    @Test
    fun `trojan and vmess keep their outbound protocol names`() {
        val vmessJson = """
            {"add":"vmess.example","port":"443","id":"22222222-2222-2222-2222-222222222222","net":"tcp","tls":"tls"}
        """.trimIndent()
        val vmess = "vmess://" + Base64.getEncoder().encodeToString(vmessJson.toByteArray(StandardCharsets.UTF_8))

        assertEquals("trojan", outboundOf("trojan://password@example.com:443?security=tls").getString("protocol"))
        assertEquals("vmess", outboundOf(vmess).getString("protocol"))
    }

    /** The builder re-parses [ProxyProfile.rawUri]; the stored summary fields don't affect the outbound. */
    private fun outboundOf(link: String): JSONObject {
        val config = JSONObject(builder.buildSocksTestConfig(profile().copy(rawUri = link), socksPort = 10_808))
        return config.getJSONArray("outbounds").getJSONObject(0)
    }

    private fun streamOf(link: String): JSONObject = outboundOf(link).getJSONObject("streamSettings")

    private fun finalMaskOf(link: String): JSONObject = streamOf(link).getJSONObject("finalmask")

    private fun hysteriaSettingsOf(link: String): JSONObject = streamOf(link).getJSONObject("hysteriaSettings")

    private fun tlsOf(outbound: JSONObject): JSONObject =
        outbound.getJSONObject("streamSettings").getJSONObject("tlsSettings")

    private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()

    private fun profile() = ProxyProfile(
        id = "profile",
        name = "Example",
        protocol = ProxyProtocol.VLESS,
        host = "example.com",
        port = 443,
        transport = ProxyTransport.RAW,
        security = ProxySecurity.REALITY,
        flow = "xtls-rprx-vision",
        source = ProxyProfileSource.MANUAL,
        sourceUrl = null,
        rawUri = "vless://id@example.com:443?security=reality&type=tcp&pbk=public-key&sni=example.org",
        fingerprint = "fingerprint",
        isSelected = true,
        isPinned = false,
        isStale = false,
        lastTestStatus = ProxyTestStatus.NOT_TESTED,
        lastLatencyMs = null,
        lastTestAt = null,
        createdAt = 0L,
        updatedAt = 0L,
        lastSeenAt = 0L,
    )

    private companion object {
        val PIN = "0123456789abcdef".repeat(4)
        val COLON_PIN = PIN.chunked(2).joinToString(":").uppercase()
    }
}
