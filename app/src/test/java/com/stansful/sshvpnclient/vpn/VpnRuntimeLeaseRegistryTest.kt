package com.stansful.sshvpnclient.vpn

import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class VpnRuntimeLeaseRegistryTest {
    @Test
    fun `foreign logical owner cannot supersede a live lease`() {
        val registry = VpnRuntimeLeaseRegistry()
        val smartOwner = Any()
        val smartLease = requireNotNull(
            registry.claim(smartOwner, VpnSessionOwner.SMART_CONNECT),
        )

        assertNull(registry.claim(Any(), VpnSessionOwner.OPEN_SOURCE))
        assertTrue(smartLease.isCurrent())

        registry.invalidate(smartOwner)
        assertNotNull(registry.claim(Any(), VpnSessionOwner.OPEN_SOURCE))
    }

    @Test
    fun `new run of the same logical owner supersedes its previous generation`() {
        val registry = VpnRuntimeLeaseRegistry()
        val first = requireNotNull(registry.claim(Any(), VpnSessionOwner.SHADOW_SSH))
        val second = requireNotNull(registry.claim(Any(), VpnSessionOwner.SHADOW_SSH))

        assertFalse(first.isCurrent())
        assertTrue(second.isCurrent())
        assertThrows(CancellationException::class.java) {
            first.requireCurrent { error("stale lease must not acquire runtime") }
        }
    }

    @Test
    fun `old service cannot invalidate a newer lease of the same logical owner`() {
        val registry = VpnRuntimeLeaseRegistry()
        val oldOwner = Any()
        requireNotNull(registry.claim(oldOwner, VpnSessionOwner.OPEN_SOURCE))
        val current = requireNotNull(registry.claim(Any(), VpnSessionOwner.OPEN_SOURCE))

        registry.invalidate(oldOwner)

        assertTrue(current.isCurrent())
    }
}
