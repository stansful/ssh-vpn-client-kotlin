package com.stansful.sshvpnclient.domain.usecase.config

import com.stansful.sshvpnclient.domain.repository.SshConfigRepository

class GetSelectedSshConfigUseCase(
    private val repository: SshConfigRepository,
) {
    suspend operator fun invoke() = repository.getSelectedConfig()
}
