package com.stansful.sshvpnclient.domain.usecase.config

import com.stansful.sshvpnclient.domain.repository.SshConfigRepository

class GetSshConfigListUseCase(
    private val repository: SshConfigRepository,
) {
    operator fun invoke() = repository.observeSummaries()
}
