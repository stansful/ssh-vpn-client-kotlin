package com.stansful.sshvpnclient.domain.usecase.proxy

import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyShareLinkParserTest {
    private val parser = ProxyShareLinkParser()

    @Test
    fun `parses vless reality vision profile`() {
        val result = parser.parse(
            "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                "?flow=xtls-rprx-vision&encryption=none&type=tcp&security=reality" +
                "&fp=chrome&sni=example.org&pbk=public-key&sid=abcd#Example",
        ) as ProxyParseResult.Success

        assertEquals(ProxyProtocol.VLESS, result.profile.protocol)
        assertEquals(ProxyTransport.RAW, result.profile.transport)
        assertEquals(ProxySecurity.REALITY, result.profile.security)
        assertEquals("Example", result.profile.name)
        assertEquals("xtls-rprx-vision", result.profile.flow)
    }

    @Test
    fun `ignores display name and query order when deduplicating`() {
        val first = parser.parse(
            "vless://id@example.com:443?security=tls&type=ws&path=%2Fsocket#One",
        ) as ProxyParseResult.Success
        val second = parser.parse(
            "vless://id@example.com:443?path=%2Fsocket&type=ws&security=tls#Two",
        ) as ProxyParseResult.Success

        assertEquals(first.profile.fingerprint, second.profile.fingerprint)
    }

    @Test
    fun `parses vmess base64 json`() {
        val json = """
            {"v":"2","ps":"VMess test","add":"vmess.example","port":"8443",
             "id":"22222222-2222-2222-2222-222222222222","net":"grpc","tls":"tls",
             "serviceName":"proxy"}
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        val result = parser.parse("vmess://$encoded") as ProxyParseResult.Success

        assertEquals(ProxyProtocol.VMESS, result.profile.protocol)
        assertEquals(ProxyTransport.GRPC, result.profile.transport)
        assertEquals(ProxySecurity.TLS, result.profile.security)
    }

    @Test
    fun `parses trojan and reports invalid lines in bulk`() {
        val results = parser.parseMany(
            """
            trojan://password@example.com:443?security=tls&type=ws#Trojan
            invalid://value
            """.trimIndent(),
        )

        val success = results.first() as ProxyParseResult.Success
        assertEquals(ProxyProtocol.TROJAN, success.profile.protocol)
        assertTrue(results.last() is ProxyParseResult.Failure)
    }
}
