package com.stansful.sshvpnclient.domain.usecase.config

import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.ValidationError

class SshConfigValidator {
    fun validate(config: SshConfig): List<ValidationError> = buildList {
        if (config.name.isBlank()) {
            add(ValidationError("name", "Name is required"))
        }
        if (config.host.isBlank()) {
            add(ValidationError("host", "Host is required"))
        }
        if (config.port !in 1..65535) {
            add(ValidationError("port", "Port must be between 1 and 65535"))
        }
        if (config.username.isBlank()) {
            add(ValidationError("username", "Username is required"))
        }
        if (config.authType == AuthType.PASSWORD && config.password.isNullOrBlank()) {
            add(ValidationError("password", "Password is required"))
        }
        if (config.authType == AuthType.PRIVATE_KEY && config.privateKeyId.isNullOrBlank()) {
            add(ValidationError("privateKeyId", "Private key is required"))
        }
        if (config.keepAliveIntervalSec <= 0) {
            add(ValidationError("keepAliveIntervalSec", "KeepAlive interval must be positive"))
        }
    }
}
