package com.stansful.sshvpnclient.domain.model

enum class AuthType {
    PASSWORD,
    PRIVATE_KEY;

    val label: String
        get() = when (this) {
            PASSWORD -> "Password"
            PRIVATE_KEY -> "Private key"
        }
}
