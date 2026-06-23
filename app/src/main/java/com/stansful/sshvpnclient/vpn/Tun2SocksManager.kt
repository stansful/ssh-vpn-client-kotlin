package com.stansful.sshvpnclient.vpn

import android.os.ParcelFileDescriptor
import com.jcraft.jsch.Session
import java.util.concurrent.atomic.AtomicReference

class Tun2SocksManager {
    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    private var isTransportPaused: Boolean = false

    private var forwarder: KotlinTunForwarder? = null
    private val degradationReason = AtomicReference<String?>(null)

    fun start(
        vpnInterface: ParcelFileDescriptor,
        sshSession: Session,
        enableUdpForwarding: Boolean,
        log: (String) -> Unit,
    ) {
        stop()
        degradationReason.set(null)
        check(vpnInterface.fileDescriptor.valid()) { "VPN interface is not valid" }
        check(sshSession.isConnected) { "SSH session is not connected" }

        if (enableUdpForwarding) {
            log("UDP forwarding requested; custom Kotlin forwarder supports TCP and DNS UDP/53 only")
        }

        val nextForwarder = KotlinTunForwarder(
            vpnInterface = vpnInterface,
            sshSession = sshSession,
            log = log,
            onDegraded = { reason ->
                if (degradationReason.compareAndSet(null, reason)) {
                    log("Kotlin TUN forwarding degradation detected: $reason")
                }
            },
        )
        forwarder = nextForwarder
        isRunning = true
        isTransportPaused = false
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
        isTransportPaused = false
        activeForwarder?.stop()
        if (wasRunning) {
            activeForwarder?.awaitStopped(STOP_WAIT_MS)
        }
        degradationReason.set(null)
    }

    fun pauseSshTransport() {
        if (isTransportPaused) return
        forwarder?.pauseSshTransport()
        isTransportPaused = true
    }

    fun resumeSshTransport(sshSession: Session) {
        check(isRunning) { "TUN forwarding is not running" }
        val activeForwarder = checkNotNull(forwarder) { "TUN forwarder is unavailable" }
        activeForwarder.resumeSshTransport(sshSession)
        isTransportPaused = false
    }

    fun resetIdleClientConnections(minimumIdleMs: Long): Int {
        if (!isRunning || isTransportPaused) return 0
        return forwarder?.resetIdleClientConnections(minimumIdleMs) ?: 0
    }

    fun consumeDegradationReason(): String? {
        return degradationReason.getAndSet(null)
    }

    private companion object {
        const val STOP_WAIT_MS = 2_000L
    }
}
