package com.stansful.sshvpnclient.domain.model

data class ValidationError(
    val field: String,
    val message: String,
)
