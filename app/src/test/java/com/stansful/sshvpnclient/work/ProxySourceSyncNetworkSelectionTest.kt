package com.stansful.sshvpnclient.work

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySourceSyncNetworkSelectionTest {
    @Test
    fun `metered physical and unmetered VPN are both rejected`() {
        val selected = selectBackgroundSyncNetwork(
            candidates = listOf(
                candidate("cellular", isNotMetered = false, isCellular = true),
                candidate("vpn", isNotVpn = false, isNotMetered = true),
            ),
            activeKey = "vpn",
        )

        assertNull(selected)
    }

    @Test
    fun `validated unmetered physical network is selected below VPN`() {
        val selected = selectBackgroundSyncNetwork(
            candidates = listOf(
                candidate("vpn", isNotVpn = false, isNotMetered = true),
                candidate("wifi", isNotMetered = true, isWifi = true),
                candidate("ethernet", isNotMetered = true, isEthernet = true),
            ),
            activeKey = "vpn",
        )

        assertEquals("ethernet", selected)
    }

    @Test
    fun `active eligible physical network wins`() {
        val selected = selectBackgroundSyncNetwork(
            candidates = listOf(
                candidate("wifi", isNotMetered = true, isWifi = true),
                candidate("ethernet", isNotMetered = true, isEthernet = true),
            ),
            activeKey = "wifi",
        )

        assertEquals("wifi", selected)
    }

    @Test
    fun `only transient IO failures are retried`() {
        assertTrue(shouldRetryProxySourceSync(IOException("temporary network failure")))
        assertFalse(shouldRetryProxySourceSync(IllegalStateException("HTTP 404")))
        assertFalse(shouldRetryProxySourceSync(IllegalArgumentException("invalid response")))
    }

    private fun candidate(
        key: String,
        isNotVpn: Boolean = true,
        isNotMetered: Boolean,
        isEthernet: Boolean = false,
        isWifi: Boolean = false,
        isCellular: Boolean = false,
    ) = BackgroundSyncNetworkCandidate(
        key = key,
        hasInternet = true,
        isValidated = true,
        isNotVpn = isNotVpn,
        isNotMetered = isNotMetered,
        isEthernet = isEthernet,
        isWifi = isWifi,
        isCellular = isCellular,
    )
}
