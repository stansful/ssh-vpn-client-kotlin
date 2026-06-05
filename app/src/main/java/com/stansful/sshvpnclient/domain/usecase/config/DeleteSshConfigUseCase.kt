package com.stansful.sshvpnclient.domain.usecase.config

import com.stansful.sshvpnclient.domain.repository.SshConfigRepository

class DeleteSshConfigUseCase(
    private val repository: SshConfigRepository,
) {
    suspend operator fun invoke(id: String) {
        repository.delete(id)
    }
}
