package com.stansful.sshvpnclient.domain.model

data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
)
