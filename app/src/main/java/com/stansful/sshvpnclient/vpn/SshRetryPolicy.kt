package com.stansful.sshvpnclient.vpn

/** What a failed SSH attempt says about trying again. */
internal enum class SshFailureKind {
    /** The host key differs from the pinned one: a retry meets the same key, and must not sign in. */
    HOST_KEY_CHANGED,

    /** The server rejected the password or key: every retry is one more failed login on the server. */
    AUTHENTICATION,

    /** Network or transport trouble that a later attempt may not meet. */
    TRANSIENT,

    /** A setup problem (missing key, bad key file, ...) that a retry repeats. */
    OTHER,
}

internal fun classifySshFailure(error: VpnConnectionException): SshFailureKind = when {
    error is SshHostKeyChangedException -> SshFailureKind.HOST_KEY_CHANGED
    error is SshAuthenticationException -> SshFailureKind.AUTHENTICATION
    isTransientSshFailure(error.message.orEmpty()) -> SshFailureKind.TRANSIENT
    else -> SshFailureKind.OTHER
}

private fun isTransientSshFailure(message: String): Boolean {
    return message.contains("timeout", ignoreCase = true) ||
        message.contains("Host unreachable", ignoreCase = true) ||
        message.contains("usable non-VPN network", ignoreCase = true) ||
        message.contains("Underlying network changed", ignoreCase = true) ||
        message.contains("another service instance", ignoreCase = true) ||
        message.contains("transport interrupted", ignoreCase = true) ||
        message.contains("Unknown connection error", ignoreCase = true) ||
        message.contains("TUN forwarding", ignoreCase = true)
}

internal sealed interface SshRetryDecision {
    data object Retry : SshRetryDecision

    /** Ends the run with the error; [diagnostic] says why it is not retried when the error alone does not. */
    data class Stop(val diagnostic: String?) : SshRetryDecision
}

/**
 * Whether one SSH connection run tries again after a failed attempt.
 *
 * - A changed host key always stops the run, before and after the first connection.
 * - An authentication failure stops it before the first connection. After it, the run retries until
 *   [maxConsecutiveAuthFailures] failures pile up without a successful sign-in ([onAuthenticated]) in
 *   between - other failures in between do not reset the count: fail2ban-style guards count failed
 *   logins, not attempts, and ban the device's address after a few.
 * - Everything else keeps the old rule: retried after the first connection, before it only if transient.
 *
 * Used by one connection loop at a time; not thread-safe.
 */
internal class SshRetryPolicy(
    private val maxConsecutiveAuthFailures: Int = DEFAULT_MAX_CONSECUTIVE_AUTH_FAILURES,
) {
    init {
        require(maxConsecutiveAuthFailures >= 1) { "maxConsecutiveAuthFailures must be at least 1" }
    }

    var consecutiveAuthFailures: Int = 0
        private set

    /** The server accepted the password or key. */
    fun onAuthenticated() {
        consecutiveAuthFailures = 0
    }

    fun onFailure(kind: SshFailureKind, everConnected: Boolean): SshRetryDecision = when (kind) {
        SshFailureKind.HOST_KEY_CHANGED -> SshRetryDecision.Stop(
            "Host key mismatch: connection stopped without retry until the new key is reviewed",
        )
        SshFailureKind.AUTHENTICATION -> {
            consecutiveAuthFailures += 1
            when {
                !everConnected -> SshRetryDecision.Stop(null)
                consecutiveAuthFailures >= maxConsecutiveAuthFailures -> SshRetryDecision.Stop(
                    "Stopped reconnecting after $consecutiveAuthFailures failed sign-ins in a row, " +
                        "so the server does not ban this device",
                )
                else -> SshRetryDecision.Retry
            }
        }
        SshFailureKind.TRANSIENT -> SshRetryDecision.Retry
        SshFailureKind.OTHER -> if (everConnected) SshRetryDecision.Retry else SshRetryDecision.Stop(null)
    }

    companion object {
        const val DEFAULT_MAX_CONSECUTIVE_AUTH_FAILURES = 3
    }
}
