package com.stansful.sshvpnclient.domain.model

data class VpnConnectionState(
    val status: VpnConnectionStatus = VpnConnectionStatus.DISCONNECTED,
    val activeConfigId: String? = null,
    val errorMessage: String? = null,
    val diagnostics: List<String> = emptyList(),
    val activeTransport: VpnTransportType? = null,
    val sessionOwner: VpnSessionOwner? = null,
)

enum class VpnTransportType {
    SSH,
    XRAY,
}

enum class VpnSessionOwner {
    SHADOW_SSH,
    OPEN_SOURCE,
    SMART_CONNECT,
}

fun VpnTransportType.defaultSessionOwner(): VpnSessionOwner {
    return when (this) {
        VpnTransportType.SSH -> VpnSessionOwner.SHADOW_SSH
        VpnTransportType.XRAY -> VpnSessionOwner.OPEN_SOURCE
    }
}
