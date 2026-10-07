package com.stansful.sshvpnclient.domain.usecase.proxy

import com.stansful.sshvpnclient.domain.model.ParsedProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `hysteria v1 links are reported as unsupported`() {
        listOf(
            "hysteria://example.com:443?protocol=udp&auth=secret&upmbps=100&downmbps=100#V1",
            "HYSTERIA://example.com:443",
        ).forEach { link ->
            assertEquals(
                link,
                ProxyParseResult.Failure(ProxyParseReasons.HYSTERIA_V1_UNSUPPORTED, unsupported = true),
                parser.parse(link),
            )
        }
    }

    @Test
    fun `unknown schemes and malformed links are invalid rather than unsupported`() {
        listOf("invalid://value", "hy2://secret@:443", "vless://@example.com:443").forEach { link ->
            val result = parser.parse(link)

            assertTrue(link, result is ProxyParseResult.Failure)
            assertFalse(link, (result as ProxyParseResult.Failure).unsupported)
        }
    }

    @Test
    fun `bulk import keeps hysteria 2 and flags hysteria v1 as unsupported`() {
        val results = parser.parseMany(
            """
            hy2://secret@example.com:443?sni=sni.example#Hy2
            hysteria://example.com:443?auth=secret#V1
            vless://id@example.com:443?security=tls&type=ws#Vless
            """.trimIndent(),
        )

        assertEquals(3, results.size)
        assertEquals(ProxyProtocol.HYSTERIA2, (results[0] as ProxyParseResult.Success).profile.protocol)
        assertEquals(
            ProxyParseResult.Failure(ProxyParseReasons.HYSTERIA_V1_UNSUPPORTED, unsupported = true),
            results[1],
        )
        assertEquals(ProxyProtocol.VLESS, (results[2] as ProxyParseResult.Success).profile.protocol)
    }

    // Fingerprints are persisted and deduplicate imports, so the existing protocols must keep theirs.
    // Each literal is the SHA-256 of the canonical string in the comment above it.

    @Test
    fun `vless fingerprint is unchanged`() {
        val link = "vless://11111111-1111-1111-1111-111111111111@Example.com:443" +
            "?type=ws&security=tls&path=%2Fws&sni=example.org#Name"

        // vless://11111111-1111-1111-1111-111111111111@example.com:443|path=/ws|security=tls|sni=example.org|type=ws
        assertEquals("fa7f1e5b42d347e99645f97b9f94f533b341e90211c5b6fccb309f7bc8c4f60e", fingerprintOf(link))
    }

    @Test
    fun `trojan fingerprint is unchanged`() {
        val link = "trojan://pass%40word@example.net:8443?security=tls&sni=example.net#Trojan"

        // trojan://pass@word@example.net:8443|security=tls|sni=example.net
        assertEquals("b33a9b27e30d6ca0146860babe2b416ba6d72c8f017af0eae7652334fd5c8450", fingerprintOf(link))
    }

    @Test
    fun `vmess fingerprint is unchanged`() {
        val json = """
            {"v":"2","ps":"VMess","add":"Vmess.Example","port":"8443","id":"22222222-2222-2222-2222-222222222222",
             "aid":"0","net":"ws","type":"none","host":"cdn.example","path":"/ws","tls":"tls","sni":"sni.example"}
        """.trimIndent()
        val link = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))

        // vmess://22222222-2222-2222-2222-222222222222@vmess.example:8443
        //     |aid=0|host=cdn.example|net=ws|path=/ws|sni=sni.example|tls=tls|type=none
        assertEquals("bd7640565ba1928c7aba02b4ce81c788f8bbd20c8b80768ca647a8d562d2430e", fingerprintOf(link))
    }

    @Test
    fun `hysteria 2 fingerprint hashes the canonical link`() {
        // hysteria2://secret@example.com:443
        assertEquals(
            "cfbbee61ad712c605ef8bfc3ef6ec05eaa258f0c5851c40cdf13603d152b6994",
            fingerprintOf("hy2://secret@Example.com#Name"),
        )
        assertEquals(
            sha256(
                "hysteria2://secret@example.com:8443|obfs=salamander|obfs-password=pw12|sni=sni.example|up=100 mbps",
            ),
            fingerprintOf(
                "hysteria2://secret@example.com:8443/?up=100&sni=sni.example&obfs=salamander&obfs-password=pw12#X",
            ),
        )
    }

    @Test
    fun `hysteria 2 fingerprint ignores scheme alias parameter aliases order name and unknown parameters`() {
        val base = fingerprintOf(
            "hysteria2://secret@example.com:443/?sni=sni.example&pinSHA256=$PIN&obfs=salamander" +
                "&obfs-password=pw12&mport=20000-50000&up=100%20mbps#One",
        )

        listOf(
            "hy2://secret@example.com?peer=sni.example&pcs=$COLON_PIN&obfs=salamander&obfs-password=pw12" +
                "&ports=20000:50000&upmbps=100#Two",
            "HY2://secret@EXAMPLE.com:443?up=100&mport=20000-50000&obfs-password=pw12&obfs=SALAMANDER" +
                "&pinsha256=${PIN.uppercase()}&sni=sni.example",
            "hysteria2://secret@example.com:443?sni=sni.example&pinSHA256=$PIN&obfs=salamander&obfs-password=pw12" +
                "&mport=20000-50000&up=100mbps&alpn=h3&fp=chrome&fastopen=1&security=none&type=tcp#Three",
        ).forEach { link -> assertEquals(link, base, fingerprintOf(link)) }
        assertEquals(
            fingerprintOf("hy2://secret@example.com?insecure=true"),
            fingerprintOf("hy2://secret@example.com:443/?allowInsecure=1"),
        )
    }

    @Test
    fun `hysteria 2 fingerprint changes with auth obfuscation password hop ports sni or port`() {
        val base = "hy2://secret@example.com:443?sni=sni.example&obfs=salamander&obfs-password=pw12&mport=20000-50000"
        val links = listOf(
            base,
            base.replace("secret@", "other@"),
            base.replace("secret@", "user:secret@"),
            base.replace("obfs-password=pw12", "obfs-password=pw122"),
            base.replace("mport=20000-50000", "mport=20000-40000"),
            base.replace("&mport=20000-50000", ""),
            base.replace("sni=sni.example", "sni=other.example"),
            base.replace(":443?", ":8443?"),
        )

        assertEquals(links.size, links.map(::fingerprintOf).toSet().size)
    }

    private fun fingerprintOf(link: String): String = success(link).fingerprint

    private fun success(link: String): ParsedProxyProfile {
        val result = parser.parse(link)
        assertTrue("$link -> $result", result is ProxyParseResult.Success)
        return (result as ProxyParseResult.Success).profile
    }

    /** Independent of the parser's own helper, so a change to either is caught. */
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> byte.toInt().and(0xff).toString(16).padStart(2, '0') }

    private companion object {
        val PIN = "0123456789abcdef".repeat(4)
        val COLON_PIN = PIN.chunked(2).joinToString(":").uppercase()
    }
}
