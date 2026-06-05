package com.stansful.sshvpnclient.domain.usecase.config

import com.stansful.sshvpnclient.domain.repository.SshConfigRepository

class SelectSshConfigUseCase(
    private val repository: SshConfigRepository,
) {
    suspend operator fun invoke(id: String) {
        repository.selectConfig(id)
    }
}
