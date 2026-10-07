package com.stansful.sshvpnclient.ui.routes

import androidx.compose.runtime.Immutable
import com.stansful.sshvpnclient.domain.model.ParsedProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.usecase.proxy.Hysteria2Parameters
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyParseReasons
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyParseResult
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import com.stansful.sshvpnclient.domain.usecase.proxy.decodeShareLinkComponent

/**
 * Why a line can't become a route (shown in the Add sheet as it is typed). [unsupported] marks a
 * readable link that uses something the engine can't run: the batch view and the import count it
 * as unsupported rather than unreadable.
 */
internal enum class LinkProblem(val message: String, val short: String, val unsupported: Boolean = false) {
    NotLink(
        "This isn’t a route link. It should start with vless://, vmess://, trojan:// or hy2://.",
        "Not a link",
    ),
    TooLong("This link is over 65,536 characters, so it can’t be saved.", "Too long"),
    NoHost("The link has no server address. Copy it again from the source.", "No server address"),
    NoPort("The link has no valid port. Add one after the server, like :443.", "No port"),
    NoUserId("The vless link has no user ID before the @.", "No user ID"),
    NoVmessId("The vmess link has no user ID. Copy it again from the source.", "No user ID"),
    NoPassword("The trojan link has no password before the @.", "No password"),
    DamagedVmess("This vmess link is damaged. Copy it again from the source.", "Damaged vmess link"),
    UnsupportedObfs(
        "This Hysteria 2 link uses an obfuscation other than Salamander or Gecko, which isn’t supported.",
        "Obfuscation not supported",
        unsupported = true,
    ),
    NoObfsPassword("The hy2 link turns on obfuscation but has no obfs-password.", "No obfs-password"),
    ShortObfsPassword(
        "The obfs-password is shorter than 4 characters, which the engine rejects.",
        "Short obfs-password",
    ),
    BadPin("The pinSHA256 in the link isn’t a valid certificate SHA-256.", "Bad pinSHA256"),
    BadEch("The ech value in the link can’t be read.", "Bad ech"),
    Subscription(
        "This looks like a subscription block, which isn’t supported. Paste the links themselves, one per line.",
        "Subscription block",
    ),
    Unreadable("This link can’t be read.", "Can’t read"),
}

/** One parsed line: a route, an unsupported scheme or a problem. */
@Immutable
internal sealed interface LinkParse {
    data class Route(val profile: ParsedProxyProfile) : LinkParse {
        /** Transport or security the engine does not know: saved, but it can't connect. */
        val unknownTransport: Boolean
            get() = profile.transport == ProxyTransport.UNKNOWN || profile.security == ProxySecurity.UNKNOWN

        /** The link had no `#name`, so the parser named it `PROTOCOL host:port`. */
        val unnamed: Boolean
            get() = profile.name == "${profile.protocol.name} ${profile.host}:${profile.port}"

        /**
         * A Hysteria 2 link that asks to skip certificate checks without pinning the certificate.
         * Xray-core can't skip them, so it connects only when the server certificate is valid.
         */
        val certificateCheckWarning: Boolean
            get() = profile.protocol == ProxyProtocol.HYSTERIA2 &&
                Hysteria2Parameters.INSECURE in profile.parameters &&
                Hysteria2Parameters.PIN_SHA256 !in profile.parameters
    }

    data class UnsupportedScheme(val scheme: String) : LinkParse

    data class Invalid(val problem: LinkProblem) : LinkParse
}

private val SchemePattern = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
private val SubscriptionPattern = Regex("^[A-Za-z0-9+/_-]{40,}={0,2}$")
private val CredentialPattern = Regex("^(\\s*(?:vless|trojan|ss)://)([^@\\s]+)@", RegexOption.IGNORE_CASE)
private val HysteriaSchemePattern = Regex("^\\s*(?:hysteria2|hy2|hysteria)://", RegexOption.IGNORE_CASE)

/** One `key=value` of a query split on `&`, as parseShareLinkQuery splits it. */
private val QueryPartPattern = Regex("([^&=]*)=([^&]*)")

/** Query keys whose values are secrets in Hysteria 2 (`obfs-password`, `auth`) and Hysteria v1 links. */
private val SecretQueryKeys = setOf("obfs-password", "auth", "auth_str", "obfsparam")
private val VmessPattern = Regex("^(\\s*vmess://).*$", RegexOption.IGNORE_CASE)

internal fun ProxyShareLinkParser.parseLine(line: String): LinkParse {
    val value = line.trim()
    val scheme = schemeOf(value)
    return when (val result = parse(value)) {
        is ProxyParseResult.Success -> LinkParse.Route(result.profile)
        is ProxyParseResult.Failure -> when {
            scheme == null -> LinkParse.Invalid(
                if (SubscriptionPattern.matches(value)) LinkProblem.Subscription else LinkProblem.NotLink,
            )
            // Known but not runnable: a Hysteria v1 link reads as an unsupported scheme, a hy2 option as a problem.
            result.unsupported -> if (result.reason == ProxyParseReasons.OBFS_UNSUPPORTED) {
                LinkParse.Invalid(LinkProblem.UnsupportedObfs)
            } else {
                LinkParse.UnsupportedScheme(scheme)
            }
            ProxyProtocol.fromScheme(scheme) == null -> LinkParse.UnsupportedScheme(scheme)
            else -> LinkParse.Invalid(problemOf(result.reason, scheme))
        }
    }
}

private fun problemOf(reason: String, scheme: String): LinkProblem = when (reason) {
    "Configuration is too large" -> LinkProblem.TooLong
    ProxyParseReasons.HOST_MISSING -> LinkProblem.NoHost
    ProxyParseReasons.PORT_INVALID -> LinkProblem.NoPort
    ProxyParseReasons.OBFS_PASSWORD_MISSING -> LinkProblem.NoObfsPassword
    ProxyParseReasons.OBFS_PASSWORD_TOO_SHORT -> LinkProblem.ShortObfsPassword
    ProxyParseReasons.PIN_INVALID -> LinkProblem.BadPin
    ProxyParseReasons.ECH_INVALID -> LinkProblem.BadEch
    "Password is missing" -> LinkProblem.NoPassword
    "UUID is missing" -> if (scheme == "vmess") LinkProblem.NoVmessId else LinkProblem.NoUserId
    "Empty configuration" -> LinkProblem.NotLink
    else -> if (scheme == "vmess") LinkProblem.DamagedVmess else LinkProblem.Unreadable
}

/** The lowercase scheme of a trimmed line (`vless`, `hy2`…), or null when it doesn't start with `scheme://`. */
private fun schemeOf(value: String): String? = SchemePattern.find(value)?.groupValues?.get(1)?.lowercase()

/**
 * Hides the secrets of a link: `vless://••••••••@host…`, `hy2://••••••••@host…?obfs-password=••••••••`,
 * `vmess://••••••••••••`. Secrets overlapping each other become one mask; masking too much is fine,
 * showing any part of a secret is not.
 */
internal fun maskLink(line: String): String {
    if (VmessPattern.matches(line)) return VmessPattern.replace(line) { it.groupValues[1] + VMESS_MASK }
    val querySecrets = secretQueryRanges(line)
    val credential = CredentialPattern.find(line)?.groups?.get(2)?.range ?: hysteriaAuthRange(line, querySecrets)
    var next = 0
    return buildString {
        mergeRanges(listOfNotNull(credential) + querySecrets).forEach { secret ->
            append(line, next, secret.first).append(MASK)
            next = secret.last + 1
        }
        append(line, next, line.length)
    }
}

/** [maskLink] line by line, so a secret's mask can't run on into the next link. */
internal fun maskLinks(text: String): String = text.lineSequence().joinToString("\n", transform = ::maskLink)

/**
 * Everything between `://` and the last `@` before the `#name`: a Hysteria auth may hold unescaped `@`,
 * `/`, spaces and the like, which the parser keeps. An `@` inside a secret query value doesn't count.
 */
private fun hysteriaAuthRange(line: String, querySecrets: List<IntRange>): IntRange? {
    val start = HysteriaSchemePattern.find(line)?.range?.last?.plus(1) ?: return null
    val at = (line.substringBefore('#').lastIndex downTo start).firstOrNull { index ->
        line[index] == '@' && querySecrets.none { index in it }
    } ?: return null
    return (start until at).takeUnless(IntRange::isEmpty)
}

/**
 * Values of secret query keys. The query is split like parseShareLinkQuery splits it (from the first `?` to
 * the `#name`, on `&`), and a key counts once decoded, trimmed and lowercased, so `obfs%2Dpassword` is caught.
 */
private fun secretQueryRanges(line: String): List<IntRange> {
    val beforeFragment = line.substringBefore('#')
    val query = beforeFragment.indexOf('?')
    if (query < 0) return emptyList()
    return QueryPartPattern.findAll(beforeFragment, query + 1)
        .filter { part -> isSecretQueryKey(part.groupValues[1]) }
        .mapNotNull { part -> part.groups[2]?.range?.takeUnless(IntRange::isEmpty) }
        .toList()
}

private fun isSecretQueryKey(rawKey: String): Boolean {
    val key = runCatching { decodeShareLinkComponent(rawKey) }.getOrDefault(rawKey)
    // `hy2://pa?ss@host?auth=…` starts the query inside the auth, so the key is what follows the last `?`.
    return key.substringAfterLast('?').trim().lowercase() in SecretQueryKeys
}

private fun mergeRanges(ranges: List<IntRange>): List<IntRange> =
    ranges.sortedBy(IntRange::first).fold(mutableListOf()) { merged, range ->
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
        merged
    }

/** Library fingerprint → route name, for duplicate detection ([exceptId] = the route being edited). */
internal fun List<ProxyProfileSummary>.namesByFingerprint(exceptId: String? = null): Map<String, String> =
    asSequence().filter { it.id != exceptId }.associate { it.fingerprint to it.name }

/** The single link typed into the Paste tab (or the Edit field). */
@Immutable
internal data class LinkAnalysis(
    val linkCount: Int,
    val parse: LinkParse?,
    val duplicateOf: String?,
) {
    val route: LinkParse.Route? get() = parse as? LinkParse.Route
    val acceptable: Boolean get() = route != null && duplicateOf == null
}

/** The status pill of the Paste tab's preview card. */
internal enum class PreviewVerdict(val text: String) {
    AlreadyAdded("Already added"),
    CantConnect("Can’t connect"),

    /** [LinkParse.Route.certificateCheckWarning]: still saved, as it connects when the server certificate is valid. */
    CheckCertificate("Check certificate"),
    LooksGood("Looks good"),
}

/** What the Paste tab's dashed preview says while there is no route to show. */
internal fun LinkAnalysis.previewPlaceholder(): String = when {
    linkCount == 0 -> "A preview appears as soon as the link can be read."
    linkCount > 1 -> "Several links here. Review them together in the batch view."
    parse is LinkParse.UnsupportedScheme -> "No preview for ${parse.scheme}:// links."
    parse is LinkParse.Invalid && parse.problem.unsupported -> "No preview for links the engine can’t run."
    else -> "Nothing to preview until the link can be read."
}

/** The most pressing [PreviewVerdict] for this route ([duplicateOf]: the library route it repeats). */
internal fun LinkParse.Route.previewVerdict(duplicateOf: String?): PreviewVerdict = when {
    duplicateOf != null -> PreviewVerdict.AlreadyAdded
    unknownTransport -> PreviewVerdict.CantConnect
    certificateCheckWarning -> PreviewVerdict.CheckCertificate
    else -> PreviewVerdict.LooksGood
}

/** Analyses [text]; in Add mode `#` lines are comments, in Edit mode every line counts. */
internal fun ProxyShareLinkParser.analyzeLink(
    text: String,
    known: Map<String, String>,
    editing: Boolean,
): LinkAnalysis {
    val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty)
        .filter { editing || !it.startsWith('#') }
        .toList()
    if (lines.size != 1) return LinkAnalysis(lines.size, null, null)
    val parse = parseLine(lines.single())
    val duplicate = (parse as? LinkParse.Route)?.let { known[it.profile.fingerprint] }
    return LinkAnalysis(1, parse, duplicate)
}

internal enum class BatchCategory { New, Duplicate, Unsupported, Invalid }

@Immutable
internal data class BatchRow(
    val name: String,
    val subtitle: String,
    val subtitleMono: Boolean,
    val category: BatchCategory,
    val label: String,
    val unknownTransport: Boolean,
    /** A new route that won't reach a self-signed server: [LinkParse.Route.certificateCheckWarning]. */
    val certificateCheckWarning: Boolean,
)

@Immutable
internal data class BatchAnalysis(
    val rows: List<BatchRow>,
    val comments: Int,
    val overflow: Int,
) {
    fun count(category: BatchCategory): Int = rows.count { it.category == category }

    val newCount: Int get() = count(BatchCategory.New)

    /** Lines found, including those over the 10,000 limit. */
    val found: Int get() = rows.size + overflow
}

/** Splits pasted text into links the way the import does (comments skipped, 10,000 at most). */
internal fun ProxyShareLinkParser.analyzeBatch(text: String, known: Map<String, String>): BatchAnalysis {
    val rows = ArrayList<BatchRow>()
    val seen = HashSet<String>()
    var comments = 0
    var overflow = 0
    text.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
        when {
            line.startsWith('#') -> comments += 1
            rows.size >= MAX_BATCH_LINES -> overflow += 1
            else -> rows += batchRow(line, known, seen)
        }
    }
    return BatchAnalysis(rows, comments, overflow)
}

private fun ProxyShareLinkParser.batchRow(
    line: String,
    known: Map<String, String>,
    seen: MutableSet<String>,
): BatchRow {
    val masked = maskLink(line).substringBefore('#')
    return when (val parse = parseLine(line)) {
        is LinkParse.Route -> {
            val profile = parse.profile
            val existing = known[profile.fingerprint]
            when {
                existing != null || profile.fingerprint in seen -> BatchRow(
                    name = profile.name,
                    subtitle = if (existing != null) "Same as $existing" else "Repeated in this paste",
                    subtitleMono = false,
                    category = BatchCategory.Duplicate,
                    label = "Duplicate",
                    unknownTransport = false,
                    certificateCheckWarning = false,
                )
                else -> {
                    seen += profile.fingerprint
                    newRow(parse)
                }
            }
        }
        is LinkParse.UnsupportedScheme -> BatchRow(
            name = fragmentName(line) ?: "${parse.scheme}:// link",
            subtitle = masked,
            subtitleMono = true,
            category = BatchCategory.Unsupported,
            label = "${parse.scheme}:// not supported",
            unknownTransport = false,
            certificateCheckWarning = false,
        )
        is LinkParse.Invalid -> BatchRow(
            // A readable link the engine can't run is named by its scheme, as an unsupported scheme is.
            name = fragmentName(line)
                ?: schemeOf(line)?.takeIf { parse.problem.unsupported }?.let { scheme -> "$scheme:// link" }
                ?: "Unreadable line",
            subtitle = masked,
            subtitleMono = true,
            category = if (parse.problem.unsupported) BatchCategory.Unsupported else BatchCategory.Invalid,
            label = parse.problem.short,
            unknownTransport = false,
            certificateCheckWarning = false,
        )
    }
}

/** A route the import will add; the subtitle says what may keep it from connecting. */
private fun newRow(route: LinkParse.Route): BatchRow {
    val profile = route.profile
    val address = "${profile.protocol.scheme} · ${profile.host}:${profile.port}"
    return BatchRow(
        name = profile.name,
        subtitle = when {
            route.unknownTransport -> "${profile.protocol.scheme} · transport not supported"
            route.certificateCheckWarning -> "$address · needs pinSHA256"
            else -> address
        },
        subtitleMono = true,
        category = BatchCategory.New,
        label = "New",
        unknownTransport = route.unknownTransport,
        certificateCheckWarning = route.certificateCheckWarning,
    )
}

private fun fragmentName(line: String): String? {
    val fragment = line.substringAfter('#', "").takeIf(String::isNotBlank) ?: return null
    return runCatching { java.net.URLDecoder.decode(fragment, Charsets.UTF_8.name()) }.getOrDefault(fragment)
        .trim().takeIf(String::isNotEmpty)
}

private const val MASK = "••••••••"
private const val VMESS_MASK = "••••••••••••"
private const val MAX_BATCH_LINES = 10_000
