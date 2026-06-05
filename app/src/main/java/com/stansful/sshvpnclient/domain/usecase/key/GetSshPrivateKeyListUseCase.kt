package com.stansful.sshvpnclient.domain.usecase.key

import com.stansful.sshvpnclient.domain.repository.SshPrivateKeyRepository

class GetSshPrivateKeyListUseCase(
    private val repository: SshPrivateKeyRepository,
) {
    operator fun invoke() = repository.observeAll()
}
