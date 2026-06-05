package com.stansful.sshvpnclient.vpn

class VpnConnectionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
