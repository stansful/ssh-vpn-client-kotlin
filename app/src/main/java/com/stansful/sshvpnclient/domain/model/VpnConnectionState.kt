package com.stansful.sshvpnclient.domain.model

data class VpnConnectionState(
    val status: VpnConnectionStatus = VpnConnectionStatus.DISCONNECTED,
    val activeConfigId: String? = null,
    val errorMessage: String? = null,
)
