package com.stansful.sshvpnclient.vpn

import kotlinx.coroutines.CancellationException

class VpnRuntimeLeaseRegistry {
    private val lock = Any()
    private var nextGeneration = 0L
    private var currentLease: VpnRuntimeLease? = null

    fun claim(owner: Any): VpnRuntimeLease = synchronized(lock) {
        VpnRuntimeLease(
            registry = this,
            owner = owner,
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
    internal val generation: Long,
) {
    fun isCurrent(): Boolean = registry.isCurrent(this)

    internal fun <T> requireCurrent(block: () -> T): T {
        return registry.requireCurrent(this, block)
    }
}
