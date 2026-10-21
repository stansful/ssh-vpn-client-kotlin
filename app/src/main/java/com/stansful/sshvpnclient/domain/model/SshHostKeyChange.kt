package com.stansful.sshvpnclient.domain.model

/** Server [configId] presented [observedFingerprint] instead of its pinned [pinnedFingerprint]. */
data class SshHostKeyChange(
    val configId: String,
    val pinnedFingerprint: String,
    val observedFingerprint: String,
)
