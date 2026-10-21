package com.stansful.sshvpnclient.domain.usecase.config

import com.stansful.sshvpnclient.domain.model.SshHostKeyChange
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.repository.SshHostKeyChangeRepository

/** The user trusts the key a server presented instead of its pinned one: pin it and drop the warning. */
class AcceptSshHostKeyUseCase(
    private val configRepository: SshConfigRepository,
    private val hostKeyChanges: SshHostKeyChangeRepository,
) {
    /**
     * Returns false if the server no longer exists or its saved fingerprint is not [change]'s pinned one
     * any more (changed since the warning). The warning is dropped either way: it no longer describes
     * what is saved, and the next connection checks that.
     */
    suspend operator fun invoke(change: SshHostKeyChange): Boolean {
        val saved = configRepository.replaceFingerprint(
            id = change.configId,
            expected = change.pinnedFingerprint,
            fingerprint = change.observedFingerprint,
        )
        hostKeyChanges.clear(change.configId)
        return saved
    }
}
