package com.stansful.sshvpnclient.data.proxy

import com.stansful.sshvpnclient.data.secret.SecretStorage
import com.stansful.sshvpnclient.domain.model.ParsedProxyProfile
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyParseResult
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyFingerprintsTest {
    private val parser = ProxyShareLinkParser()

    @Test
    fun `a row under the older fingerprint whose link parses to the current one is matched`() = runBlocking {
        val plus = parsed(PLUS_LINK)
        val row = Row(id = "plus", fingerprint = checkNotNull(plus.legacyFingerprint), secretId = "secret-plus")
        val secrets = Secrets(mapOf(row.secretId to PLUS_LINK))

        val matches = matches(listOf(plus), listOf(row), secrets)

        assertEquals(mapOf(plus.fingerprint to row), matches)
        assertEquals(listOf(listOf(row.secretId)), secrets.reads)
    }

    @Test
    fun `a row another link finds by its current fingerprint is not taken by an older one`() = runBlocking {
        // An older build saved the plus link; its fingerprint is now the space link's current one.
        val plus = parsed(PLUS_LINK)
        val space = parsed(SPACE_LINK)
        val row = Row(id = "shared", fingerprint = space.fingerprint, secretId = "secret-shared")
        val secrets = Secrets(mapOf(row.secretId to PLUS_LINK))

        val matches = matches(listOf(plus, space), listOf(row), secrets)

        // The space link keeps its exact match; the row is never handed to both.
        assertTrue(matches.isEmpty())
        assertTrue(secrets.reads.isEmpty())
    }

    @Test
    fun `links without an older fingerprint read nothing`() = runBlocking {
        val link = parsed("vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls#A")
        val secrets = Secrets(emptyMap())

        assertTrue(matches(listOf(link), emptyList(), secrets).isEmpty())
        assertTrue(secrets.reads.isEmpty())
    }

    private suspend fun matches(
        profiles: List<ParsedProxyProfile>,
        rows: List<Row>,
        secrets: SecretStorage,
    ): Map<String, Row> = legacyFingerprintMatches(
        profiles = profiles,
        rowsByFingerprint = rows.associateBy(Row::fingerprint),
        secretIdOf = Row::secretId,
        secretStorage = secrets,
        parser = parser,
    )

    private fun parsed(link: String): ParsedProxyProfile = (parser.parse(link) as ProxyParseResult.Success).profile

    private data class Row(val id: String, val fingerprint: String, val secretId: String)

    private class Secrets(private val values: Map<String, String>) : SecretStorage {
        val reads = mutableListOf<List<String>>()

        override suspend fun saveSecret(id: String, value: String) {
            error("read-only")
        }

        override suspend fun getSecret(id: String): String? = values[id]

        override suspend fun getSecrets(ids: Collection<String>): Map<String, String> {
            reads += ids.toList()
            return ids.mapNotNull { id -> values[id]?.let { id to it } }.toMap()
        }

        override suspend fun deleteSecret(id: String) {
            error("read-only")
        }
    }

    private companion object {
        const val PLUS_LINK = "trojan://correct+horse@trojan.example.test:443?security=tls#Plus"
        const val SPACE_LINK = "trojan://correct%20horse@trojan.example.test:443?security=tls#Space"
    }
}
