package com.stansful.sshvpnclient.ui.routes

import androidx.compose.runtime.Immutable
import com.stansful.sshvpnclient.domain.model.ParsedProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyParseResult
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser

/** Why a line can't become a route (shown in the Add sheet as it is typed). */
internal enum class LinkProblem(val message: String, val short: String) {
    NotLink(
        "This isn’t a route link. It should start with vless://, vmess:// or trojan://.",
        "Not a link",
    ),
    TooLong("This link is over 65,536 characters, so it can’t be saved.", "Too long"),
    NoHost("The link has no server address. Copy it again from the source.", "No server address"),
    NoPort("The link has no valid port. Add one after the server, like :443.", "No port"),
    NoUserId("The vless link has no user ID before the @.", "No user ID"),
    NoVmessId("The vmess link has no user ID. Copy it again from the source.", "No user ID"),
    NoPassword("The trojan link has no password before the @.", "No password"),
    DamagedVmess("This vmess link is damaged. Copy it again from the source.", "Damaged vmess link"),
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
    }

    data class UnsupportedScheme(val scheme: String) : LinkParse

    data class Invalid(val problem: LinkProblem) : LinkParse
}

private val SchemePattern = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
private val SubscriptionPattern = Regex("^[A-Za-z0-9+/_-]{40,}={0,2}$")
private val CredentialPattern = Regex("^(\\s*(?:vless|trojan|ss)://)([^@\\s]+)@", RegexOption.IGNORE_CASE)
private val VmessPattern = Regex("^(\\s*vmess://).*$", RegexOption.IGNORE_CASE)
private val SupportedSchemes = setOf("vless", "vmess", "trojan")

internal fun ProxyShareLinkParser.parseLine(line: String): LinkParse {
    val value = line.trim()
    val scheme = SchemePattern.find(value)?.groupValues?.get(1)?.lowercase()
    return when (val result = parse(value)) {
        is ProxyParseResult.Success -> LinkParse.Route(result.profile)
        is ProxyParseResult.Failure -> when {
            scheme == null -> LinkParse.Invalid(
                if (SubscriptionPattern.matches(value)) LinkProblem.Subscription else LinkProblem.NotLink,
            )
            scheme !in SupportedSchemes -> LinkParse.UnsupportedScheme(scheme)
            else -> LinkParse.Invalid(problemOf(result.reason, scheme))
        }
    }
}

private fun problemOf(reason: String, scheme: String): LinkProblem = when (reason) {
    "Configuration is too large" -> LinkProblem.TooLong
    "Host is missing" -> LinkProblem.NoHost
    "Port is invalid" -> LinkProblem.NoPort
    "Password is missing" -> LinkProblem.NoPassword
    "UUID is missing" -> if (scheme == "vmess") LinkProblem.NoVmessId else LinkProblem.NoUserId
    "Empty configuration" -> LinkProblem.NotLink
    else -> if (scheme == "vmess") LinkProblem.DamagedVmess else LinkProblem.Unreadable
}

/** Hides the secret of a link: `vless://••••••••@host…`, `vmess://••••••••••••`. */
internal fun maskLink(line: String): String {
    CredentialPattern.find(line)?.let { match ->
        return match.groupValues[1] + MASK + line.substring(match.range.last)
    }
    if (VmessPattern.matches(line)) return VmessPattern.replace(line) { it.groupValues[1] + VMESS_MASK }
    return line
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
    known: Map<String,
    String>,
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
                )
                else -> {
                    seen += profile.fingerprint
                    BatchRow(
                        name = profile.name,
                        subtitle = if (parse.unknownTransport) {
                            "${profile.protocol.scheme} · transport not supported"
                        } else {
                            "${profile.protocol.scheme} · ${profile.host}:${profile.port}"
                        },
                        subtitleMono = true,
                        category = BatchCategory.New,
                        label = "New",
                        unknownTransport = parse.unknownTransport,
                    )
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
        )
        is LinkParse.Invalid -> BatchRow(
            name = fragmentName(line) ?: "Unreadable line",
            subtitle = masked,
            subtitleMono = true,
            category = BatchCategory.Invalid,
            label = parse.problem.short,
            unknownTransport = false,
        )
    }
}

private fun fragmentName(line: String): String? {
    val fragment = line.substringAfter('#', "").takeIf(String::isNotBlank) ?: return null
    return runCatching { java.net.URLDecoder.decode(fragment, Charsets.UTF_8.name()) }.getOrDefault(fragment)
        .trim().takeIf(String::isNotEmpty)
}

private const val MASK = "••••••••"
private const val VMESS_MASK = "••••••••••••"
private const val MAX_BATCH_LINES = 10_000
