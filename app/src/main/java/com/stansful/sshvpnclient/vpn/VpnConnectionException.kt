package com.stansful.sshvpnclient.vpn

open class VpnConnectionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
