package com.stansful.sshvpnclient.vpn

import android.os.ParcelFileDescriptor
import com.jcraft.jsch.Session

class Tun2SocksManager {
    var isRunning: Boolean = false
        private set

    fun start(
        vpnInterface: ParcelFileDescriptor,
        sshSession: Session,
        enableUdpForwarding: Boolean,
    ) {
        check(vpnInterface.fileDescriptor.valid()) { "VPN interface is not valid" }
        check(sshSession.isConnected) { "SSH session is not connected" }
        isRunning = true

        // Integration point for a real tun2socks engine. For MVP data modelling and
        // service lifecycle are in place; packet forwarding should be wired here.
        @Suppress("UNUSED_VARIABLE")
        val udpForwardingRequested = enableUdpForwarding
    }

    fun stop() {
        isRunning = false
    }
}
