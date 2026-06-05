package com.stansful.sshvpnclient.domain.usecase.key

import com.stansful.sshvpnclient.domain.repository.SshPrivateKeyRepository

class GetSshPrivateKeyByIdUseCase(
    private val repository: SshPrivateKeyRepository,
) {
    suspend operator fun invoke(id: String) = repository.getById(id)
}
