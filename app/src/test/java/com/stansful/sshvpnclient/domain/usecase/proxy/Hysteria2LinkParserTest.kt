package com.stansful.sshvpnclient.domain.usecase.proxy

import com.stansful.sshvpnclient.domain.model.ParsedProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Written from the Hysteria 2 URI scheme and the normalization rules, not from the parser's code: the link
 * itself (schemes, host, ports, auth, name, fingerprint). Parameters are covered by [Hysteria2ParametersTest].
 */
class Hysteria2LinkParserTest {
    private val parser = ProxyShareLinkParser()

    @Test
    fun `parses hy2 link into a tls over hysteria profile`() {
        val link = "hy2://secret@example.com:8443?sni=sni.example#Office"
        val profile = parsed(link)

        assertEquals("Office", profile.name)
        assertEquals(ProxyProtocol.HYSTERIA2, profile.protocol)
        assertEquals("example.com", profile.host)
        assertEquals(8443, profile.port)
        assertEquals(ProxyTransport.HYSTERIA, profile.transport)
        assertEquals(ProxySecurity.TLS, profile.security)
        assertNull(profile.flow)
        assertEquals("secret", profile.credential)
        assertEquals(link, profile.rawUri)
        assertEquals(mapOf("sni" to "sni.example"), profile.parameters)
    }

    @Test
    fun `accepts hysteria2 and hy2 schemes in any letter case`() {
        listOf("hysteria2", "hy2", "HYSTERIA2", "Hy2").forEach { scheme ->
            val profile = parsed("$scheme://secret@example.com:8443")

            assertEquals(scheme, ProxyProtocol.HYSTERIA2, profile.protocol)
            assertEquals(scheme, "example.com", profile.host)
            assertEquals(scheme, 8443, profile.port)
            assertEquals(scheme, "secret", profile.credential)
        }
    }

    @Test
    fun `port defaults to 443 and a plain link has no parameters`() {
        listOf(
            "hy2://secret@example.com",
            "hy2://secret@example.com/",
            "hy2://secret@example.com?",
            "hy2://secret@example.com#Name",
        ).forEach { link ->
            val profile = parsed(link)

            assertEquals(link, 443, profile.port)
            assertEquals(link, emptyMap<String, String>(), profile.parameters)
        }
    }

    @Test
    fun `reads a link with a slash between authority and query`() {
        val profile = parsed(
            "hysteria2://secret@example.com:8443/?sni=sni.example&obfs=salamander&obfs-password=obfs-pw#Home",
        )

        assertEquals("example.com", profile.host)
        assertEquals(8443, profile.port)
        assertEquals("secret", profile.credential)
        assertEquals("Home", profile.name)
        assertEquals(
            mapOf("sni" to "sni.example", "obfs" to "salamander", "obfs-password" to "obfs-pw"),
            profile.parameters,
        )
    }

    @Test
    fun `keeps ipv6 host in brackets`() {
        val withPort = parsed("hy2://secret@[2001:db8::1]:8443/?sni=sni.example")
        val withoutPort = parsed("hy2://secret@[2001:db8::1]")

        assertEquals("[2001:db8::1]", withPort.host)
        assertEquals(8443, withPort.port)
        assertEquals("HYSTERIA2 [2001:db8::1]:8443", withPort.name)
        assertEquals("[2001:db8::1]", withoutPort.host)
        assertEquals(443, withoutPort.port)
    }

    @Test
    fun `multi port authority connects to the first port and hops over the whole list`() {
        val mixed = parsed("hy2://secret@example.com:443,20000-50000/?sni=sni.example")
        val rangeOnly = parsed("hy2://secret@example.com:20000-50000")
        val singles = parsed("hy2://secret@example.com:1000,2000,3000")
        val ipv6 = parsed("hy2://secret@[2001:db8::1]:443,5000-6000")

        assertEquals(443, mixed.port)
        assertEquals("443,20000-50000", mixed.parameters["ports"])
        assertEquals("HYSTERIA2 example.com:443", mixed.name)
        assertEquals(20_000, rangeOnly.port)
        assertEquals("20000-50000", rangeOnly.parameters["ports"])
        assertEquals(1_000, singles.port)
        assertEquals("1000,2000,3000", singles.parameters["ports"])
        assertEquals("[2001:db8::1]", ipv6.host)
        assertEquals(443, ipv6.port)
        assertEquals("443,5000-6000", ipv6.parameters["ports"])
    }

    @Test
    fun `rejects ports outside 1 to 65535 and malformed port lists`() {
        listOf("0", "65536", "abc", "5-3", "443,0", "443,abc", "20000-70000", "443,").forEach { ports ->
            assertEquals(
                ports,
                ProxyParseResult.Failure(ProxyParseReasons.PORT_INVALID),
                parser.parse("hy2://secret@example.com:$ports?sni=sni.example"),
            )
        }
        assertEquals(
            ProxyParseResult.Failure(ProxyParseReasons.PORT_INVALID),
            parser.parse("hy2://secret@[2001:db8::1]:70000"),
        )
    }

    @Test
    fun `rejects links without a host`() {
        listOf(
            "hy2://",
            "hy2://secret@",
            "hy2://secret@:443",
            "hy2://secret@/?sni=sni.example",
            "hysteria2://?sni=sni.example#Name",
        ).forEach { link ->
            assertEquals(link, ProxyParseResult.Failure(ProxyParseReasons.HOST_MISSING), parser.parse(link))
        }
    }

    @Test
    fun `auth may be absent or empty or carried by the auth query parameter`() {
        val fromQuery = parsed("hy2://example.com:443?auth=from%20query&sni=sni.example")

        assertEquals("", parsed("hy2://example.com:443").credential)
        assertEquals("", parsed("hy2://@example.com:443").credential)
        assertEquals("from query", fromQuery.credential)
        assertEquals(mapOf("sni" to "sni.example"), fromQuery.parameters)
        assertEquals("userinfo", parsed("hy2://userinfo@example.com?auth=query").credential)
    }

    @Test
    fun `auth is the percent-decoded userinfo including colon and at sign`() {
        val plain = parsed("hy2://user:pass@example.com:8443")

        assertEquals("user:pa@ss", parsed("hy2://user%3Apa%40ss@example.com").credential)
        assertEquals("user:pa ss", parsed("hy2://user:pa%20ss@example.com").credential)
        assertEquals("user:pass", plain.credential)
        assertEquals("example.com", plain.host)
        assertEquals(8443, plain.port)
    }

    @Test
    fun `names the profile from the percent-decoded fragment`() {
        val profile = parsed("hy2://secret@example.com#Office%20%E2%80%94%20Berlin")

        assertEquals("Office — Berlin", profile.name)
    }

    @Test
    fun `default name is protocol host and port and never a secret`() {
        val profile = parsed("hy2://super-secret@example.com:8443?obfs=salamander&obfs-password=obfs-secret#%20")

        assertEquals("HYSTERIA2 example.com:8443", profile.name)
        assertFalse(profile.name.contains("secret"))
        assertEquals("HYSTERIA2 example.com:443", parsed("hy2://super-secret@example.com").name)
    }

    @Test
    fun `merges overlapping and adjacent hop ranges so the list can't blow up`() {
        val repeated = List(64) { "1-65535" }.joinToString(",")

        assertEquals("1-65535", parameters("mport=$repeated")["ports"])
        assertEquals("1-65535", parsed("hy2://secret@example.com:$repeated").parameters["ports"])
        assertEquals("20000-40000,50000", parameters("ports=30000-40000,50000,20000-30000,30001")["ports"])
        assertEquals(30_000, parsed("hy2://secret@example.com:30000-40000,20000-29999").port)
        assertEquals(
            "20000-40000",
            parsed("hy2://secret@example.com:30000-40000,20000-29999").parameters["ports"],
        )
    }

    @Test
    fun `a literal plus in the userinfo stays a plus like in the official client`() {
        assertEquals("abc+def", parsed("hy2://abc+def@example.com").credential)
        assertEquals("abc+def", parsed("hy2://abc%2Bdef@example.com").credential)
    }

    @Test
    fun `fingerprint escapes values so a secret can't imitate another parameter`() {
        val crafted = parsed("hy2://s@example.com?obfs=salamander&obfs-password=xxxx%7Csni%3Dy").fingerprint
        val honest = parsed("hy2://s@example.com?obfs=salamander&obfs-password=xxxx&sni=y").fingerprint

        assertFalse(crafted == honest)
    }

    @Test
    fun `reads hop ports from mport or ports with dash or colon ranges`() {
        assertEquals("20000-50000", parameters("mport=20000-50000")["ports"])
        assertEquals("20000-50000", parameters("mport=20000:50000")["ports"])
        assertEquals("443,20000-30000", parameters("ports=443,20000:30000")["ports"])
        assertEquals("3000-4000", parameters("ports=1000-2000&mport=3000-4000")["ports"])
        assertEquals(8443, parsed("hy2://secret@example.com:8443?mport=20000-50000").port)
    }

    @Test
    fun `authority port list wins over mport and an invalid mport is ignored`() {
        val authority = parsed("hy2://secret@example.com:443,1000-2000?mport=3000-4000")

        assertEquals("443,1000-2000", authority.parameters["ports"])
        listOf("abc", "5-3", "443,70000", "").forEach { mport ->
            val profile = parsed("hy2://secret@example.com:8443?mport=$mport")

            assertEquals(mport, 8443, profile.port)
            assertEquals(mport, emptyMap<String, String>(), profile.parameters)
        }
    }

    private fun parsed(link: String): ParsedProxyProfile {
        val result = parser.parse(link)
        assertTrue("$link -> $result", result is ProxyParseResult.Success)
        return (result as ProxyParseResult.Success).profile
    }

    private fun parameters(query: String): Map<String, String> =
        parsed("hy2://secret@example.com:443?$query").parameters
}
