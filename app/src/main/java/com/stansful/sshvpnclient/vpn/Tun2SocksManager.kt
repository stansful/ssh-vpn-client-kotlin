package com.stansful.sshvpnclient.vpn

import android.os.ParcelFileDescriptor
import com.jcraft.jsch.Session

class Tun2SocksManager {
    @Volatile
    var isRunning: Boolean = false
        private set

    private var forwarder: KotlinTunForwarder? = null

    fun start(
        vpnInterface: ParcelFileDescriptor,
        sshSession: Session,
        enableUdpForwarding: Boolean,
        log: (String) -> Unit,
    ) {
        stop()
        check(vpnInterface.fileDescriptor.valid()) { "VPN interface is not valid" }
        check(sshSession.isConnected) { "SSH session is not connected" }

        if (enableUdpForwarding) {
            log("UDP forwarding requested; custom Kotlin forwarder supports TCP and DNS UDP/53 only")
        }

        val nextForwarder = KotlinTunForwarder(
            vpnInterface = vpnInterface,
            sshSession = sshSession,
            log = log,
        )
        forwarder = nextForwarder
        isRunning = true
        nextForwarder.start(
            onStopped = { reason ->
                if (isRunning) {
                    log("Kotlin TUN forwarding engine stopped unexpectedly: $reason")
                    isRunning = false
                }
            },
        )
        log("Kotlin TUN forwarding engine started")
    }

    fun stop() {
        val activeForwarder = forwarder
        forwarder = null
        val wasRunning = isRunning
        isRunning = false
        activeForwarder?.stop()
        if (wasRunning) {
            activeForwarder?.awaitStopped(STOP_WAIT_MS)
        }
    }

    private companion object {
        const val STOP_WAIT_MS = 500L
    }
}
