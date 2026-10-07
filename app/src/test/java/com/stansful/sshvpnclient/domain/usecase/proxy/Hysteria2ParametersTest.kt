package com.stansful.sshvpnclient.domain.usecase.proxy

import com.stansful.sshvpnclient.domain.model.ParsedProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Written from the Hysteria 2 URI scheme and the normalization rules, not from the parser's code: the query
 * parameters and the links they make the parser reject. Parameter keys are spelled out instead of read from
 * [Hysteria2Parameters]: they feed the persisted fingerprint, so renaming one must break a test.
 */
class Hysteria2ParametersTest {
    private val parser = ProxyShareLinkParser()

    @Test
    fun `sni wins over peer and peer alone sets the server name`() {
        assertEquals("sni.example", parameters("sni=sni.example&peer=peer.example")["sni"])
        assertEquals("sni.example", parameters("peer=peer.example&sni=sni.example")["sni"])
        assertEquals(mapOf("sni" to "peer.example"), parameters("peer=peer.example"))
    }

    @Test
    fun `normalizes certificate pins from pinSHA256 and pcs`() {
        assertEquals(PIN, parameters("pinSHA256=$COLON_PIN")["pinsha256"])
        assertEquals(PIN, parameters("pcs=$COLON_PIN")["pinsha256"])
        assertEquals(PIN, parameters("pinsha256=${PIN.uppercase()}")["pinsha256"])
        assertEquals("$PIN,$OTHER_PIN", parameters("pinSHA256=$COLON_PIN,$OTHER_PIN")["pinsha256"])
        assertEquals(PIN, parameters("pcs=$OTHER_PIN&pinSHA256=$PIN")["pinsha256"])
        assertFalse(parameters("pcs=$PIN").containsKey("pcs"))
    }

    @Test
    fun `rejects a link whose pin is not 64 hex characters instead of connecting without it`() {
        listOf("abc", PIN.dropLast(1), "${PIN}0", "g".repeat(64), "abc,$PIN").forEach { pin ->
            assertEquals(
                pin,
                ProxyParseResult.Failure(ProxyParseReasons.PIN_INVALID),
                parser.parse("hy2://secret@example.com:443?pinSHA256=$pin"),
            )
        }
        assertFalse(parameters("pinSHA256=").containsKey("pinsha256"))
    }

    @Test
    fun `pins may use dash or colon separators like the official client accepts`() {
        val dashed = PIN.chunked(2).joinToString("-").uppercase()

        assertEquals(PIN, parameters("pinSHA256=$dashed")["pinsha256"])
        assertEquals(PIN, parameters("pinSHA256=$COLON_PIN")["pinsha256"])
    }

    @Test
    fun `insecure is normalized to 1 for truthy spellings and absent otherwise`() {
        listOf(
            "insecure=1",
            "insecure=true",
            "insecure=TRUE",
            "insecure=t",
            "insecure=yes",
            "allowInsecure=1",
            "allow_insecure=true",
        ).forEach { query -> assertEquals(query, mapOf("insecure" to "1"), parameters(query)) }
        listOf("insecure=0", "insecure=false", "insecure=no", "insecure=", "allowInsecure=0").forEach { query ->
            assertEquals(query, emptyMap<String, String>(), parameters(query))
        }
    }

    @Test
    fun `keeps salamander obfuscation with its decoded password`() {
        assertEquals(
            mapOf("obfs" to "salamander", "obfs-password" to "p@ss word"),
            parameters("obfs=salamander&obfs-password=p%40ss%20word"),
        )
        assertEquals(
            mapOf("obfs" to "salamander", "obfs-password" to "pw12"),
            parameters("obfs=Salamander&obfs-password=pw12"),
        )
    }

    @Test
    fun `salamander without a password is invalid but not unsupported`() {
        listOf("obfs=salamander", "obfs=salamander&obfs-password=").forEach { query ->
            assertEquals(
                query,
                ProxyParseResult.Failure(ProxyParseReasons.OBFS_PASSWORD_MISSING, unsupported = false),
                parser.parse("hy2://secret@example.com?$query"),
            )
        }
    }

    @Test
    fun `obfs none or a lone obfs password means no obfuscation`() {
        assertEquals(emptyMap<String, String>(), parameters("obfs=none&obfs-password=ignored"))
        assertEquals(emptyMap<String, String>(), parameters("obfs-password=ignored"))
    }

    @Test
    fun `keeps gecko obfuscation which also needs a password`() {
        assertEquals(
            mapOf("obfs" to "gecko", "obfs-password" to "pw12"),
            parameters("obfs=Gecko&obfs-password=pw12"),
        )
        assertEquals(
            ProxyParseResult.Failure(ProxyParseReasons.OBFS_PASSWORD_MISSING),
            parser.parse("hy2://secret@example.com:443?obfs=gecko"),
        )
    }

    @Test
    fun `obfuscation passwords shorter than 4 bytes are rejected like xray and the official client do`() {
        listOf("salamander", "gecko").forEach { obfs ->
            assertEquals(
                obfs,
                ProxyParseResult.Failure(ProxyParseReasons.OBFS_PASSWORD_TOO_SHORT),
                parser.parse("hy2://secret@example.com:443?obfs=$obfs&obfs-password=abc"),
            )
        }
        assertEquals("ab\u00e9", parameters("obfs=salamander&obfs-password=ab%C3%A9")["obfs-password"])
    }

    @Test
    fun `secrets keep surrounding spaces while other values are trimmed`() {
        assertEquals(
            mapOf("sni" to "sni.example", "obfs" to "salamander", "obfs-password" to " pw12 "),
            parameters("sni=%20sni.example%20&obfs=salamander&obfs-password=%20pw12%20"),
        )
        assertEquals(" query auth ", parsed("hy2://example.com?auth=%20query%20auth%20").credential)
    }

    @Test
    fun `obfs plain means no obfuscation`() {
        assertEquals(emptyMap<String, String>(), parameters("obfs=plain"))
    }

    @Test
    fun `a blank parameter does not hide its alias`() {
        assertEquals(
            mapOf("sni" to "peer.example", "pinsha256" to PIN, "ports" to "20000-50000", "up" to "100 mbps"),
            parameters("sni=&peer=peer.example&pinSHA256=&pcs=$PIN&mport=&ports=20000-50000&up=&upmbps=100"),
        )
        assertEquals(
            ProxyParseResult.Failure(ProxyParseReasons.OBFS_PASSWORD_MISSING),
            parser.parse("hy2://secret@example.com:443?obfs=salamander&obfs-password="),
        )
    }

    @Test
    fun `normalizes the ech config list to padded standard base64`() {
        assertEquals("AAEC/w==", parameters("ech=AAEC%2Fw%3D%3D")["ech"])
        assertEquals("AAEC/w==", parameters("ech=AAEC_w")["ech"])
        assertEquals("AAEC+w==", parameters("ech=AAEC+w==")["ech"])
        assertNull(parameters("ech=")["ech"])
    }

    @Test
    fun `rejects a link whose ech config can't be read instead of connecting without ech`() {
        listOf("https://1.1.1.1/dns-query", "example.com%2Bhttps://1.1.1.1/dns-query", "not*base64").forEach { ech ->
            assertEquals(
                ech,
                ProxyParseResult.Failure(ProxyParseReasons.ECH_INVALID),
                parser.parse("hy2://secret@example.com:443?ech=$ech"),
            )
        }
    }

    @Test
    fun `unknown obfuscation is reported as unsupported`() {
        assertEquals(
            ProxyParseResult.Failure(ProxyParseReasons.OBFS_UNSUPPORTED, unsupported = true),
            parser.parse("hy2://secret@example.com:443?obfs=xplus&obfs-password=pw12"),
        )
    }

    @Test
    fun `normalizes hop interval to seconds of at least 5`() {
        mapOf(
            "30" to "30",
            "30s" to "30",
            "10-30" to "10-30",
            "10s-30s" to "10-30",
            "3" to "5",
            "3-30" to "5-30",
            "0-60" to "30-60",
        ).forEach { (raw, expected) ->
            assertEquals(raw, expected, parameters("mport=20000-50000&hop-interval=$raw")["hop-interval"])
        }
        // 0 means "unset" in every other client, so Xray keeps its 30 s default.
        listOf("abc", "", "fast-slow", "0", "0s", "0-0").forEach { raw ->
            assertEquals(raw, mapOf("ports" to "20000-50000"), parameters("mport=20000-50000&hop-interval=$raw"))
        }
    }

    @Test
    fun `hop interval needs hop ports`() {
        val ranged = parsed("hy2://secret@example.com:20000-50000?hop-interval=15")

        assertEquals(emptyMap<String, String>(), parameters("hop-interval=30"))
        assertEquals(mapOf("ports" to "20000-50000", "hop-interval" to "15"), ranged.parameters)
    }

    @Test
    fun `normalizes brutal bandwidth reading bare numbers as mbps`() {
        mapOf(
            "up=100" to "100 mbps",
            "up=100%20mbps" to "100 mbps",
            "up=100mbps" to "100 mbps",
            "up=100%20Mbps" to "100 mbps",
            "upmbps=50" to "50 mbps",
            "up=100&upmbps=50" to "100 mbps",
            "up=1%20g" to "1 g",
            "up=600%20kbps" to "600 kbps",
        ).forEach { (query, expected) -> assertEquals(query, mapOf("up" to expected), parameters(query)) }
        assertEquals(mapOf("down" to "200 mbps"), parameters("downmbps=200"))
        assertEquals(mapOf("down" to "1 gbps"), parameters("down=1%20gbps"))
    }

    @Test
    fun `drops bandwidth below what brutal accepts or in an unknown format`() {
        listOf("0", "1%20kbps", "500%20kbps", "100%20b", "fast", "-5", "100%20mbit", "").forEach { value ->
            assertEquals(value, emptyMap<String, String>(), parameters("up=$value&down=$value"))
        }
    }

    @Test
    fun `keeps only normalized parameters and ignores everything else`() {
        val profile = parsed(
            "hy2://secret@example.com:443?alpn=h3&fp=chrome&security=none&type=tcp&fastopen=1&lazy=1" +
                "&congestion=reno&sni=sni.example&obfs=salamander&obfs-password=pw12&mport=20000-50000" +
                "&hop-interval=30&up=100&down=200&pinSHA256=$PIN&insecure=1",
        )

        assertEquals(ProxySecurity.TLS, profile.security)
        assertEquals(ProxyTransport.HYSTERIA, profile.transport)
        assertEquals(
            mapOf(
                "sni" to "sni.example",
                "pinsha256" to PIN,
                "insecure" to "1",
                "obfs" to "salamander",
                "obfs-password" to "pw12",
                "ports" to "20000-50000",
                "hop-interval" to "30",
                "up" to "100 mbps",
                "down" to "200 mbps",
            ),
            profile.parameters,
        )
    }


    private fun parsed(link: String): ParsedProxyProfile {
        val result = parser.parse(link)
        assertTrue("$link -> $result", result is ProxyParseResult.Success)
        return (result as ProxyParseResult.Success).profile
    }

    private fun parameters(query: String): Map<String, String> =
        parsed("hy2://secret@example.com:443?$query").parameters

    private companion object {
        val PIN = "0123456789abcdef".repeat(4)
        val OTHER_PIN = "fedcba9876543210".repeat(4)
        val COLON_PIN = PIN.chunked(2).joinToString(":").uppercase()
    }
}
