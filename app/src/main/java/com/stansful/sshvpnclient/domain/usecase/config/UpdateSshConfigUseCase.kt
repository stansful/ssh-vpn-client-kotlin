package com.stansful.sshvpnclient.domain.usecase.config

import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.ValidationException
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository

class UpdateSshConfigUseCase(
    private val repository: SshConfigRepository,
    private val validator: SshConfigValidator = SshConfigValidator(),
) {
    suspend operator fun invoke(config: SshConfig) {
        val errors = validator.validate(config)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        repository.update(config)
    }
}
