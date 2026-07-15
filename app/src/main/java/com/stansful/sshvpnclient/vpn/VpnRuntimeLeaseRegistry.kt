package com.stansful.sshvpnclient.vpn

import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import kotlinx.coroutines.CancellationException

class VpnRuntimeLeaseRegistry {
    private val lock = Any()
    private var nextGeneration = 0L
    private var currentLease: VpnRuntimeLease? = null

    /**
     * Claims the process-wide VPN runtime without ever superseding a different logical mode.
     * Transport switching must invalidate the previous lease first; a stale service command can
     * therefore be rejected without cancelling the live owner it raced with.
     */
    fun claim(owner: Any, sessionOwner: VpnSessionOwner): VpnRuntimeLease? = synchronized(lock) {
        val activeLease = currentLease
        if (activeLease != null && activeLease.sessionOwner != sessionOwner) return@synchronized null
        VpnRuntimeLease(
            registry = this,
            owner = owner,
            sessionOwner = sessionOwner,
            generation = ++nextGeneration,
        ).also { lease -> currentLease = lease }
    }

    fun invalidate(owner: Any) {
        synchronized(lock) {
            if (currentLease?.owner === owner) {
                currentLease = null
            }
        }
    }

    internal fun isCurrent(lease: VpnRuntimeLease): Boolean = synchronized(lock) {
        currentLease === lease
    }

    internal fun <T> requireCurrent(
        lease: VpnRuntimeLease,
        block: () -> T,
    ): T {
        if (!isCurrent(lease)) {
            throw CancellationException("VPN runtime lease was superseded")
        }
        return block()
    }
}

class VpnRuntimeLease internal constructor(
    private val registry: VpnRuntimeLeaseRegistry,
    internal val owner: Any,
    internal val sessionOwner: VpnSessionOwner,
    internal val generation: Long,
) {
    fun isCurrent(): Boolean = registry.isCurrent(this)

    internal fun <T> requireCurrent(block: () -> T): T {
        return registry.requireCurrent(this, block)
    }
}
