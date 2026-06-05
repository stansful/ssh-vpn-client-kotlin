package com.stansful.sshvpnclient.domain.usecase.key

import com.stansful.sshvpnclient.domain.repository.SshPrivateKeyRepository

class GetSshPrivateKeyUsageCountUseCase(
    private val repository: SshPrivateKeyRepository,
) {
    suspend operator fun invoke(keyId: String) = repository.getUsageCount(keyId)
}
