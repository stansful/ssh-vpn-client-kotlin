package com.stansful.sshvpnclient.vpn

import android.os.ParcelFileDescriptor
import com.jcraft.jsch.Session
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

class Tun2SocksManager {
    private val lifecycleLock = Any()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    private var isTransportPaused: Boolean = false

    @Volatile
    private var forwarder: KotlinTunForwarder? = null

    private var activeOwner: Any? = null
    private var activeRunToken: Any? = null
    private val degradationReason = AtomicReference<String?>(null)

    fun start(
        owner: Any,
        lease: VpnRuntimeLease,
        vpnInterface: ParcelFileDescriptor,
        sshSession: Session,
        enableUdpForwarding: Boolean,
        maxActiveTcpSessions: Int = DEFAULT_MAX_ACTIVE_TCP_SESSIONS,
        sshChannelWindowBytes: Int = DEFAULT_SSH_CHANNEL_WINDOW_BYTES,
        maxPendingUploadBytesPerFlow: Int = DEFAULT_MAX_PENDING_UPLOAD_BYTES_PER_FLOW,
        tunWriteQueueCapacity: Int = DEFAULT_TUN_WRITE_QUEUE_CAPACITY,
        outboundPacketPoolCapacity: Int = DEFAULT_OUTBOUND_PACKET_POOL_CAPACITY,
        log: (String) -> Unit,
    ) {
        start(
            owner = owner,
            lease = lease,
            vpnInterface = vpnInterface,
            sshSession = sshSession,
            enableUdpForwarding = enableUdpForwarding,
            tunMtu = VpnTunnelMode.SSH.mtu,
            sshChannelWindowBytes = sshChannelWindowBytes,
            maxPendingUploadBytesPerFlow = maxPendingUploadBytesPerFlow,
            tunWriteQueueCapacity = tunWriteQueueCapacity,
            outboundPacketPoolCapacity = outboundPacketPoolCapacity,
            maxActiveTcpSessions = maxActiveTcpSessions,
            log = log,
        )
    }

    fun start(
        owner: Any,
        lease: VpnRuntimeLease,
        vpnInterface: ParcelFileDescriptor,
        sshSession: Session,
        enableUdpForwarding: Boolean,
        tunMtu: Int,
        sshChannelWindowBytes: Int,
        maxPendingUploadBytesPerFlow: Int = DEFAULT_MAX_PENDING_UPLOAD_BYTES_PER_FLOW,
        tunWriteQueueCapacity: Int = DEFAULT_TUN_WRITE_QUEUE_CAPACITY,
        outboundPacketPoolCapacity: Int = DEFAULT_OUTBOUND_PACKET_POOL_CAPACITY,
        maxActiveTcpSessions: Int = DEFAULT_MAX_ACTIVE_TCP_SESSIONS,
        log: (String) -> Unit,
    ) {
        require(lease.owner === owner) { "TUN owner must match runtime lease" }
        lease.requireCurrent {
            synchronized(lifecycleLock) {
                if (activeOwner != null && activeOwner !== owner) {
                    throw VpnConnectionException("TUN runtime belongs to another service instance")
                }
                stopLocked(awaitTermination = true)
                degradationReason.set(null)
                check(vpnInterface.fileDescriptor.valid()) { "VPN interface is not valid" }
                check(sshSession.isConnected) { "SSH session is not connected" }

                if (enableUdpForwarding) {
                    log("UDP forwarding requested; custom Kotlin forwarder supports TCP and DNS UDP/53 only")
                }

                val runToken = Any()
                val nextForwarder = KotlinTunForwarder(
                    vpnInterface = vpnInterface,
                    sshSession = sshSession,
                    log = log,
                    onDegraded = { reason, transportGeneration ->
                        val accepted = synchronized(lifecycleLock) {
                            activeRunToken === runToken &&
                                !isTransportPaused &&
                                forwarder?.isDegradationSignalCurrent(transportGeneration) == true &&
                                degradationReason.compareAndSet(null, reason)
                        }
                        if (accepted) {
                            log("Kotlin TUN forwarding degradation detected: $reason")
                        }
                    },
                    config = TunForwarderConfig(
                        tunMtu = tunMtu,
                        sshChannelWindowBytes = sshChannelWindowBytes,
                        maxPendingUploadBytesPerFlow = maxPendingUploadBytesPerFlow,
                        tunWriteQueueCapacity = tunWriteQueueCapacity,
                        outboundPacketPoolCapacity = outboundPacketPoolCapacity,
                        maxActiveTcpSessions = maxActiveTcpSessions,
                    ),
                )
                forwarder = nextForwarder
                activeOwner = owner
                activeRunToken = runToken
                isRunning = true
                isTransportPaused = false
                try {
                    nextForwarder.start(
                        onStopped = { reason ->
                            val unexpectedStop = synchronized(lifecycleLock) {
                                if (activeRunToken !== runToken || forwarder !== nextForwarder || !isRunning) {
                                    false
                                } else {
                                    isRunning = false
                                    isTransportPaused = false
                                    activeOwner = null
                                    activeRunToken = null
                                    forwarder = null
                                    degradationReason.set(null)
                                    true
                                }
                            }
                            if (unexpectedStop) {
                                log("Kotlin TUN forwarding engine stopped unexpectedly: $reason")
                            }
                        },
                    )
                } catch (error: Exception) {
                    if (activeRunToken === runToken && forwarder === nextForwarder) {
                        stopLocked(awaitTermination = false)
                    }
                    throw error
                }
                if (!lease.isCurrent()) {
                    stopLocked(awaitTermination = false)
                    throw CancellationException("TUN runtime lease was superseded")
                }
                log("Kotlin TUN forwarding engine started")
            }
        }
    }

    fun stop(owner: Any) {
        stop(owner = owner, awaitTermination = true)
    }

    fun stop(owner: Any, awaitTermination: Boolean) {
        synchronized(lifecycleLock) {
            if (activeOwner !== owner) return
            stopLocked(awaitTermination)
        }
    }

    private fun stopLocked(awaitTermination: Boolean) {
        val activeForwarder = forwarder
        forwarder = null
        activeOwner = null
        activeRunToken = null
        val wasRunning = isRunning
        isRunning = false
        isTransportPaused = false
        activeForwarder?.stop()
        if (wasRunning && awaitTermination) {
            activeForwarder?.awaitStopped(STOP_WAIT_MS)
        }
        degradationReason.set(null)
    }

    fun pauseSshTransport(owner: Any) {
        synchronized(lifecycleLock) {
            if (activeOwner !== owner) return
            if (isTransportPaused) return
            forwarder?.pauseSshTransport()
            isTransportPaused = true
            degradationReason.set(null)
        }
    }

    fun resumeSshTransport(owner: Any, sshSession: Session) {
        synchronized(lifecycleLock) {
            check(activeOwner === owner) { "TUN forwarding belongs to another service instance" }
            check(isRunning) { "TUN forwarding is not running" }
            val activeForwarder = checkNotNull(forwarder) { "TUN forwarder is unavailable" }
            activeForwarder.resumeSshTransport(sshSession)
            degradationReason.set(null)
            isTransportPaused = false
        }
    }

    fun resetIdleClientConnections(owner: Any, minimumIdleMs: Long): Int {
        return synchronized(lifecycleLock) {
            if (activeOwner !== owner) return@synchronized 0
            if (!isRunning || isTransportPaused) return@synchronized 0
            forwarder?.resetIdleClientConnections(minimumIdleMs) ?: 0
        }
    }

    fun consumeDegradationReason(owner: Any): String? {
        return synchronized(lifecycleLock) {
            if (activeOwner !== owner) return@synchronized null
            degradationReason.getAndSet(null)
        }
    }

    fun isRunning(owner: Any): Boolean {
        return synchronized(lifecycleLock) { activeOwner === owner && isRunning }
    }

    private companion object {
        const val STOP_WAIT_MS = 2_000L
        const val DEFAULT_SSH_CHANNEL_WINDOW_BYTES = 4 * 1024 * 1024
        const val DEFAULT_MAX_PENDING_UPLOAD_BYTES_PER_FLOW = 512 * 1024
        const val DEFAULT_TUN_WRITE_QUEUE_CAPACITY = 256
        const val DEFAULT_OUTBOUND_PACKET_POOL_CAPACITY = 64
        const val DEFAULT_MAX_ACTIVE_TCP_SESSIONS = 128
    }
}
