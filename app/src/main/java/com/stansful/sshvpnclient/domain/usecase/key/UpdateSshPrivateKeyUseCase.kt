package com.stansful.sshvpnclient.domain.usecase.key

import com.stansful.sshvpnclient.domain.model.SshPrivateKey
import com.stansful.sshvpnclient.domain.model.ValidationException
import com.stansful.sshvpnclient.domain.repository.SshPrivateKeyRepository

class UpdateSshPrivateKeyUseCase(
    private val repository: SshPrivateKeyRepository,
    private val validator: SshPrivateKeyValidator = SshPrivateKeyValidator(),
) {
    suspend operator fun invoke(key: SshPrivateKey) {
        val errors = validator.validate(key)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        repository.update(key)
    }
}
