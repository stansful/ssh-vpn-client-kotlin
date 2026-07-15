package com.stansful.sshvpnclient.ui.smartconnect

import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.model.XrayCoreAsset
import com.stansful.sshvpnclient.domain.model.XrayCoreRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartConnectViewModelPolicyTest {
    @Test
    fun `ranked dropdown excludes russian markers unavailable and stale profiles`() {
        val profiles = listOf(
            profile("slow", 300L),
            profile("🇷🇺 blocked", 1L),
            profile("fast", 20L),
            profile("unavailable", 2L, status = ProxyTestStatus.UNAVAILABLE),
            profile("stale", 3L, stale = true),
        )

        assertEquals(
            listOf("fast", "slow"),
            rankAvailableSmartProfiles(profiles).map(ProxyProfileSummary::name),
        )
    }

    @Test
    fun `smart connect cannot start while core installation is in progress`() {
        val state = SmartConnectUiState(
            xrayCoreAvailable = true,
            xrayCoreUpdate = SmartXrayCoreUpdateUiState(
                runtimeAbi = "arm64-v8a",
                isDownloading = true,
            ),
        )

        assertFalse(state.canStart)
    }

    @Test
    fun `pending transport switch immediately exposes an active stoppable control`() {
        val state = SmartConnectUiState(
            xrayCoreAvailable = true,
            isStartPending = true,
        )

        assertTrue(state.isActive)
        assertFalse(state.canStart)
    }

    @Test
    fun `core update exposes only the exact runtime ABI asset`() {
        val arm64 = coreAsset("arm64-v8a")
        val state = SmartXrayCoreUpdateUiState(
            runtimeAbi = "arm64-v8a",
            release = XrayCoreRelease(
                versionName = "v1",
                title = "Xray",
                releaseUrl = "https://example.com/release",
                runtimeAbi = "arm64-v8a",
                assets = listOf(coreAsset("x86_64"), arm64),
            ),
        )

        assertEquals(arm64, state.compatibleAsset)
    }

    private fun profile(
        name: String,
        latencyMs: Long,
        status: ProxyTestStatus = ProxyTestStatus.AVAILABLE,
        stale: Boolean = false,
    ) = ProxyProfileSummary(
        id = "id-$name",
        name = name,
        protocol = ProxyProtocol.VLESS,
        host = "example.com",
        port = 443,
        transport = ProxyTransport.RAW,
        security = ProxySecurity.TLS,
        flow = null,
        fingerprint = "fingerprint-$name",
        source = ProxyProfileSource.REMOTE,
        isSelected = false,
        isPinned = false,
        isStale = stale,
        lastTestStatus = status,
        lastLatencyMs = latencyMs,
        updatedAt = 0L,
    )

    private fun coreAsset(abi: String) = XrayCoreAsset(
        abi = abi,
        name = "libxray-$abi.aar",
        downloadUrl = "https://example.com/$abi",
        sizeBytes = 1L,
        sha256Digest = null,
    )
}
