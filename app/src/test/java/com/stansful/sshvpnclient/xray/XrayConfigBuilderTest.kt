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
        isStale = false,
        lastTestStatus = ProxyTestStatus.NOT_TESTED,
        lastLatencyMs = null,
        lastTestAt = null,
        createdAt = 0L,
        updatedAt = 0L,
        lastSeenAt = 0L,
    )
}
