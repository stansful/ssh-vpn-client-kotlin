package com.stansful.sshvpnclient.vpn

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class VpnRuntimeLeaseRegistryTest {
    @Test
    fun `new claim supersedes previous service and command lease`() {
        val registry = VpnRuntimeLeaseRegistry()
        val firstOwner = Any()
        val first = registry.claim(firstOwner)
        val second = registry.claim(Any())

        assertFalse(first.isCurrent())
        assertTrue(second.isCurrent())
        assertThrows(CancellationException::class.java) {
            first.requireCurrent { error("stale lease must not acquire runtime") }
        }
    }

    @Test
    fun `old service cannot invalidate a newer owner`() {
        val registry = VpnRuntimeLeaseRegistry()
        val oldOwner = Any()
        registry.claim(oldOwner)
        val current = registry.claim(Any())

        registry.invalidate(oldOwner)

        assertTrue(current.isCurrent())
    }
}
