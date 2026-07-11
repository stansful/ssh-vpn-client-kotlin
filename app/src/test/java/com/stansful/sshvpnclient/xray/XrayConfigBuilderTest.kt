package com.stansful.sshvpnclient.xray

import com.stansful.sshvpnclient.domain.model.ProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class XrayConfigBuilderTest {
    private val builder = XrayConfigBuilder(ProxyShareLinkParser())

    @Test
    fun `builds tun config with reality outbound`() {
        val config = JSONObject(builder.buildTunConfig(profile()))
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)

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
}
