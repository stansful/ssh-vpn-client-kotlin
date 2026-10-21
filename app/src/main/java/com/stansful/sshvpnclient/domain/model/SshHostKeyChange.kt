package com.stansful.sshvpnclient.domain.model

/**
 * Server [configId] presented [observedFingerprint] instead of its pinned [pinnedFingerprint].
 * [afterSignIn]: found only once sign-in had completed (the key was trusted on first use and
 * [pinnedFingerprint] was saved during that sign-in), so the password or key had been used already;
 * otherwise sign-in was stopped before.
 */
data class SshHostKeyChange(
    val configId: String,
    val pinnedFingerprint: String,
    val observedFingerprint: String,
    val afterSignIn: Boolean = false,
)
