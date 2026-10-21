package com.stansful.sshvpnclient.data.proxy

import com.stansful.sshvpnclient.data.secret.SecretStorage
import com.stansful.sshvpnclient.domain.model.ParsedProxyProfile
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyParseResult
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser

/**
 * The rows that [profiles] were saved to under their older fingerprint, keyed by the profile's
 * current one.
 *
 * Older builds read a `+` in a VLESS or Trojan credential as a space, and the fingerprint of a row
 * saved then hashes the credential that way ([ParsedProxyProfile.legacyFingerprint]). The same link
 * imported again no longer finds that row by its fingerprint: a pasted link would be added twice, and
 * a sync would add a twin and leave the row - maybe the selected or a pinned one - to go stale.
 *
 * A row that only shares the older fingerprint is not enough: a link whose credential has a real
 * space has that fingerprint too. A row counts only when its stored link parses to the profile's
 * current fingerprint, so a row whose link can't be read stays apart, and so does a row another
 * profile in [profiles] already finds by its current fingerprint. [rowsByFingerprint] holds the rows
 * found by both fingerprints; only the few rows found by an older one are read.
 */
internal suspend fun <E : Any> legacyFingerprintMatches(
    profiles: Collection<ParsedProxyProfile>,
    rowsByFingerprint: Map<String, E>,
    secretIdOf: (E) -> String,
    secretStorage: SecretStorage,
    parser: ProxyShareLinkParser,
): Map<String, E> {
    val claimed = profiles.mapNotNullTo(HashSet()) { parsed -> rowsByFingerprint[parsed.fingerprint] }
    val candidates = profiles.mapNotNull { parsed ->
        if (parsed.fingerprint in rowsByFingerprint) return@mapNotNull null
        val row = parsed.legacyFingerprint?.let(rowsByFingerprint::get) ?: return@mapNotNull null
        (parsed to row).takeUnless { row in claimed }
    }
    if (candidates.isEmpty()) return emptyMap()
    val storedLinks = secretStorage.getSecrets(candidates.map { (_, row) -> secretIdOf(row) })
    return buildMap {
        candidates.forEach { (parsed, row) ->
            val storedLink = storedLinks[secretIdOf(row)] ?: return@forEach
            val stored = parser.parse(storedLink) as? ProxyParseResult.Success ?: return@forEach
            if (stored.profile.fingerprint == parsed.fingerprint) put(parsed.fingerprint, row)
        }
    }
}
