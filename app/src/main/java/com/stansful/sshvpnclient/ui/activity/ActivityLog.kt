package com.stansful.sshvpnclient.ui.activity

import androidx.compose.runtime.Immutable

/** Severity dot of a log line (see [logSeverity]). */
internal enum class LogSeverity(val spoken: String) {
    Info("Info"),
    Ok("OK"),
    Warning("Warning"),
    Error("Error"),
}

/** Filter chips above the log. [Mode] is "SSH" for Server sessions and "Routes" for Auto / Routes. */
internal enum class ActivityFilter {
    All,
    Problems,
    Network,
    Mode,
}

/**
 * One diagnostics line split into its `HH:mm:ss` [time] and [message]. [key] is stable for the life
 * of the screen (see [ActivityLogTracker]) so the list keeps its position when old lines are evicted.
 */
@Immutable
internal data class ActivityLine(
    val key: Long,
    val raw: String,
    val time: String,
    val message: String,
    val severity: LogSeverity,
    val isNetwork: Boolean,
    val isSsh: Boolean,
    val isRoutes: Boolean,
) {
    val isProblem: Boolean get() = severity == LogSeverity.Warning || severity == LogSeverity.Error

    fun matches(filter: ActivityFilter, sshMode: Boolean): Boolean = when (filter) {
        ActivityFilter.All -> true
        ActivityFilter.Problems -> isProblem
        ActivityFilter.Network -> isNetwork
        ActivityFilter.Mode -> if (sshMode) isSsh else isRoutes
    }
}

internal fun parseActivityLine(key: Long, raw: String): ActivityLine {
    val timed = TIME_PREFIX.containsMatchIn(raw)
    val time = if (timed) raw.substring(0, TIME_LENGTH) else ""
    val message = if (timed) raw.substring(TIME_LENGTH + 1) else raw
    return ActivityLine(
        key = key,
        raw = raw,
        time = time,
        message = message,
        severity = logSeverity(message),
        isNetwork = NETWORK_WORDS.containsMatchIn(message),
        isSsh = SSH_WORDS.containsMatchIn(message),
        isRoutes = ROUTES_WORDS.containsMatchIn(message),
    )
}

/**
 * Severity heuristic over the English diagnostics written by the VPN services (case-insensitive,
 * whole words), checked in this order:
 * 1. Error words — `failed`, `failure`, `error`, `exception`, `fatal`, `mismatch`, `dead`, `denied`,
 *    `revoked`, `interrupted`, `stopped unexpectedly`, `not found`, `unavailable`, `timed out`,
 *    `timeout`, `server rejected`. When the same line says the app is already recovering (`retrying`,
 *    `keeping`, `trying`, `instead`, `fall back`, `continuing`, `discarding`, `failing over`) it is a
 *    [LogSeverity.Warning] instead ("Live check failed (1/3); keeping the route").
 * 2. Recovery words — `cleared`, `restored`, `matched`, `stopped blocking` → [LogSeverity.Ok].
 * 3. Warning words — `warning`, `warn`, `not verified`, `retry`/`retrying`, `reconnecting in`,
 *    `did not answer`, `reset`, `blocking`, `stall`, `saturated`, `limit reached`, `pressure`,
 *    `not applied`, `dropped`/`dropping`, `skipping`, `disabled`, `reaches no`, `no active network`.
 * 4. Success words — `connected`, `answered`, `verified`, `refreshed`, `succeeded`, `ok`, `is active`,
 *    `engine started`, `loaded` → [LogSeverity.Ok].
 * 5. Anything else is [LogSeverity.Info].
 */
internal fun logSeverity(message: String): LogSeverity = when {
    ERROR_WORDS.containsMatchIn(message) ->
        if (RECOVERING_WORDS.containsMatchIn(message)) LogSeverity.Warning else LogSeverity.Error
    RECOVERED_WORDS.containsMatchIn(message) -> LogSeverity.Ok
    WARNING_WORDS.containsMatchIn(message) -> LogSeverity.Warning
    SUCCESS_WORDS.containsMatchIn(message) -> LogSeverity.Ok
    else -> LogSeverity.Info
}

/** Whether the server's host key was checked against the fingerprint saved for it. */
internal enum class HostKeyPin {
    NotPinned,
    Matched,
    Mismatch,
    Unknown,
}

/** The "Server host key" highlight: the full fingerprint and what the log says about pinning it. */
@Immutable
internal data class FingerprintHighlight(
    val fingerprint: String,
    val pin: HostKeyPin,
)

/** Highlights pulled out of the log (only built from the lines still kept). */
@Immutable
internal data class ActivityHighlights(
    val fingerprint: FingerprintHighlight?,
    val telegramCallsBlocked: Boolean,
) {
    val isEmpty: Boolean get() = fingerprint == null && !telegramCallsBlocked
}

/**
 * Finds the last `Server host key fingerprint: SHA256:…` line (written by the SSH client on the
 * first attempt after Connect and every 5th retry) and the Telegram VoIP verdict of the TUN layer
 * (`… reaches no TCP port of the Telegram VoIP hosts …`).
 */
internal fun activityHighlights(lines: List<ActivityLine>): ActivityHighlights {
    var fingerprint: String? = null
    var pin = HostKeyPin.Unknown
    var telegram = false
    for (line in lines) {
        val message = line.message
        val index = message.indexOf(FINGERPRINT_PREFIX)
        if (index >= 0) {
            fingerprint = message.substring(index + FINGERPRINT_PREFIX.length).trim().substringBefore(' ')
                .ifEmpty { fingerprint }
        }
        when {
            message.contains(FINGERPRINT_MISMATCH) -> pin = HostKeyPin.Mismatch
            message.contains(FINGERPRINT_MATCHED) -> pin = HostKeyPin.Matched
            message.contains(HOST_NOT_VERIFIED) -> pin = HostKeyPin.NotPinned
        }
        if (message.contains(TELEGRAM_VERDICT)) telegram = true
    }
    return ActivityHighlights(
        fingerprint = fingerprint?.let { FingerprintHighlight(it, pin) },
        telegramCallsBlocked = telegram,
    )
}

/** `SHA256:9f2cLqVb…T7Hk` → `SHA256:9f2cLq…7Hk` (the card shows the middle-elided form). */
internal fun shortFingerprint(fingerprint: String): String {
    val colon = fingerprint.indexOf(':')
    val prefix = if (colon >= 0) fingerprint.substring(0, colon + 1) else ""
    val body = fingerprint.substring(prefix.length)
    if (body.length <= SHORT_HEAD + SHORT_TAIL + 1) return fingerprint
    return prefix + body.take(SHORT_HEAD) + "…" + body.takeLast(SHORT_TAIL)
}

/**
 * Turns successive diagnostics snapshots (a bounded list: new lines appended, old ones evicted from
 * the front, cleared on connect) into [ActivityLine]s with stable, increasing keys. Lines that were
 * already parsed are reused.
 */
internal class ActivityLogTracker {
    private var previous: List<String> = emptyList()
    private var parsed: List<ActivityLine> = emptyList()
    private var nextKey = 0L

    fun update(lines: List<String>): List<ActivityLine> {
        if (lines == previous) return parsed
        val evicted = evictedPrefix(previous, lines)
        val kept = if (evicted == null) emptyList() else parsed.subList(evicted, parsed.size)
        val appended = lines.subList(kept.size, lines.size).map { raw -> parseActivityLine(nextKey++, raw) }
        previous = lines
        parsed = kept + appended
        return parsed
    }
}

/**
 * How many lines were evicted from the front of [old] so that the remainder starts [new]; null
 * when [new] does not continue [old] (the log was cleared or replaced).
 */
internal fun evictedPrefix(old: List<String>, new: List<String>): Int? {
    if (old.isEmpty()) return 0
    for (evicted in old.indices) {
        val keptSize = old.size - evicted
        if (keptSize > new.size) continue
        if (new[0] != old[evicted] || new[keptSize - 1] != old.last()) continue
        if (new.subList(0, keptSize) == old.subList(evicted, old.size)) return evicted
    }
    return null
}

private const val TIME_LENGTH = 8
private val TIME_PREFIX = Regex("^\\d{2}:\\d{2}:\\d{2} ")

private const val FINGERPRINT_PREFIX = "Server host key fingerprint:"
private const val FINGERPRINT_MISMATCH = "Fingerprint mismatch"
private const val FINGERPRINT_MATCHED = "Fingerprint matched"
private const val HOST_NOT_VERIFIED = "host identity is not verified"
private const val TELEGRAM_VERDICT = "reaches no TCP port of the Telegram VoIP hosts"
private const val SHORT_HEAD = 6
private const val SHORT_TAIL = 3

private fun words(vararg alternatives: String): Regex =
    Regex("\\b(?:${alternatives.joinToString("|")})\\b", RegexOption.IGNORE_CASE)

private val ERROR_WORDS = words(
    "failed", "failure", "error", "exception", "fatal", "mismatch", "dead", "denied", "revoked",
    "interrupted", "stopped unexpectedly", "not found", "unavailable", "timed out", "timeout",
    "server rejected",
)
private val RECOVERING_WORDS = words(
    "retrying", "keeping", "trying", "instead", "fall back", "falling back", "continuing", "discarding",
    "failing over",
)
private val RECOVERED_WORDS = words("cleared", "restored", "matched", "stopped blocking")
private val WARNING_WORDS = words(
    "warning", "warn", "not verified", "retry", "retrying", "reconnecting in", "did not answer", "reset",
    "blocking", "stall", "saturated", "limit reached", "pressure", "not applied", "dropped", "dropping",
    "skipping", "disabled", "reaches no", "no active network",
)
private val SUCCESS_WORDS = words(
    "connected", "answered", "verified", "refreshed", "succeeded", "ok", "is active", "engine started",
    "loaded",
)

/**
 * "Network" chip: the phone's networks and the packet layer — `network`, `DNS`, `Wi-Fi`, `cellular`,
 * `ethernet`, `TUN`, `TCP`, `UDP`, `QUIC`, `STUN`, `socket`, `interface`, `MTU`, `flow(s)`, `Doze`,
 * `Data Saver`, `underlying`, `resolver`.
 */
private val NETWORK_WORDS = words(
    "network", "networks", "dns", "wi-fi", "wifi", "cellular", "ethernet", "tun", "tcp", "udp", "quic",
    "stun", "socket", "interface", "mtu", "flow", "flows", "doze", "data saver", "underlying",
    "resolver",
)

/**
 * "SSH" chip (Server sessions): `SSH`, `JSch`, `fingerprint`, `host key`, `host identity`, `auth…`,
 * `private key`, `keepalive`, `reconnect…`, `terminal`.
 */
private val SSH_WORDS = words(
    "ssh", "jsch", "fingerprint", "host key", "host identity", "auth", "authentication", "private key",
    "keepalive", "reconnect", "reconnecting", "terminal",
)

/**
 * "Routes" chip (Auto / Routes sessions): `Smart Connect`, `Xray`, `route(s)`, `tunnel(s)`,
 * `catalog`, `source`, `health`, `profile(s)`, `configuration(s)`, `live check`, `verified`, `public`.
 */
private val ROUTES_WORDS = words(
    "smart connect", "xray", "route", "routes", "tunnel", "tunnels", "catalog", "source", "health",
    "profile", "profiles", "configuration", "configurations", "live check", "verified", "public",
)
