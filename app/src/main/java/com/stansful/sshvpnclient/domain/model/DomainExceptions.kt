package com.stansful.sshvpnclient.domain.model

class ValidationException(
    val errors: List<ValidationError>,
) : IllegalArgumentException(errors.firstOrNull()?.message ?: "Validation failed")

class KeyInUseException(
    val usageCount: Int,
) : IllegalStateException(
    "This key is used by $usageCount configurations. Remove it from these configurations before deleting.",
)
