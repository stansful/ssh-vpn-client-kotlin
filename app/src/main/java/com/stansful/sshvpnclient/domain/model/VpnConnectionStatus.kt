package com.stansful.sshvpnclient.domain.model

enum class VpnConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTING,
    ERROR,
}
