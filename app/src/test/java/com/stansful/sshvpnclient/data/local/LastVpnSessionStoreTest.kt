package com.stansful.sshvpnclient.data.local

import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastVpnSessionStoreTest {
    @Test
    fun `every owner survives a storage round trip`() {
        VpnSessionOwner.entries.forEach { owner ->
            assertEquals(owner, vpnSessionOwnerFromStorageValue(owner.toStorageValue()))
        }
    }

    @Test
    fun `storage values do not follow enum names`() {
        assertEquals("shadow-ssh", VpnSessionOwner.SHADOW_SSH.toStorageValue())
        assertEquals("opensource", VpnSessionOwner.OPEN_SOURCE.toStorageValue())
        assertEquals("smart-connect", VpnSessionOwner.SMART_CONNECT.toStorageValue())
    }

    @Test
    fun `missing or unknown storage values mean no recorded mode`() {
        assertNull(vpnSessionOwnerFromStorageValue(null))
        assertNull(vpnSessionOwnerFromStorageValue(""))
        assertNull(vpnSessionOwnerFromStorageValue("SHADOW_SSH"))
        assertNull(vpnSessionOwnerFromStorageValue("wireguard"))
    }
}
