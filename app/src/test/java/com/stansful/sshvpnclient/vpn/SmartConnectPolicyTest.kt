package com.stansful.sshvpnclient.vpn

import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartConnectPolicyTest {
    @Test
    fun `health cadence backs off after warmup and while screen is off`() {
        assertEquals(10_000L, smartHealthCheckIntervalMs(0L, true, false))
        assertEquals(10_000L, smartHealthCheckIntervalMs(59_999L, false, false))
        assertEquals(30_000L, smartHealthCheckIntervalMs(60_000L, true, false))
        assertEquals(120_000L, smartHealthCheckIntervalMs(60_000L, false, false))
        assertEquals(300_000L, smartHealthCheckIntervalMs(0L, true, true))
    }

    @Test
    fun `catalog retry uses bounded exponential schedule`() {
        assertEquals(30_000L, smartCatalogRetryDelayMs(0))
        assertEquals(60_000L, smartCatalogRetryDelayMs(1))
        assertEquals(120_000L, smartCatalogRetryDelayMs(2))
        assertEquals(300_000L, smartCatalogRetryDelayMs(3))
        assertEquals(900_000L, smartCatalogRetryDelayMs(4))
        assertEquals(900_000L, smartCatalogRetryDelayMs(100))
    }

    @Test
    fun `best candidate is verified fresh deterministic and not excluded`() {
        val candidates = listOf(
            profile("slow", 80L),
            profile("beta", 20L),
            profile("Alpha", 20L),
            profile("stale", 1L, stale = true),
            profile("🇷🇺 fastest-but-blocked", 1L),
            profile("failed", null, status = ProxyTestStatus.UNAVAILABLE),
        )

        assertEquals("Alpha", selectBestSmartCandidate(candidates)?.name)
        assertEquals(
            "beta",
            selectBestSmartCandidate(candidates, setOf("fingerprint-Alpha"))?.name,
        )
        assertNull(selectBestSmartCandidate(candidates, candidates.mapTo(hashSetOf()) { it.fingerprint }))
    }

    @Test
    fun `russian flag marks a Smart Connect profile for exclusion`() {
        assertEquals(true, isSmartConnectExcludedProfileName("🇷🇺 Moscow"))
        assertEquals(true, isSmartConnectExcludedProfileName("Route 🇷🇺 01"))
        assertEquals(false, isSmartConnectExcludedProfileName("Netherlands 01"))
    }

    @Test
    fun `batch probes leave three seconds for finalization inside hard deadline`() {
        val hardDeadlineNanos = 60_000_000_000L

        assertEquals(3_000L, SMART_CONNECT_FINALIZATION_RESERVE_MS)
        assertEquals(57_000_000_000L, smartConnectProbeDeadlineNanos(hardDeadlineNanos))
    }

    @Test
    fun `only completed tunnel outcomes are terminal and persistable`() {
        assertFalse(isTerminalSmartTunnelResult(ProxyTestStatus.NOT_TESTED))
        assertFalse(isTerminalSmartTunnelResult(ProxyTestStatus.RUNNING))
        assertTrue(isTerminalSmartTunnelResult(ProxyTestStatus.AVAILABLE))
        assertTrue(isTerminalSmartTunnelResult(ProxyTestStatus.UNAVAILABLE))
        assertTrue(isTerminalSmartTunnelResult(ProxyTestStatus.UNSUPPORTED))
    }

    @Test
    fun `partial terminal batch results accumulate safely and preserve fingerprints`() = runBlocking {
        val total = 500
        val accumulator = SmartTerminalResultAccumulator(
            fingerprintsById = (0 until total).associate { index ->
                "profile-$index" to "fingerprint-$index"
            },
        )

        (0 until total).map { index ->
            async(Dispatchers.Default) {
                accumulator.recordCompleted(
                    ProxyTunnelTestResult(
                        profileId = "profile-$index",
                        status = if (index % 2 == 0) {
                            ProxyTestStatus.AVAILABLE
                        } else {
                            ProxyTestStatus.UNAVAILABLE
                        },
                    ),
                )
            }
        }.awaitAll()

        val snapshot = accumulator.snapshot()
        assertEquals(total, snapshot.size)
        assertEquals("fingerprint-0", snapshot.first { it.profileId == "profile-0" }.profileFingerprint)
        assertEquals(
            "fingerprint-499",
            snapshot.first { it.profileId == "profile-499" }.profileFingerprint,
        )
    }

    @Test
    fun `normal batch return removes provisional result downgraded to not tested`() {
        val accumulator = SmartTerminalResultAccumulator(mapOf("profile" to "fingerprint"))
        accumulator.recordCompleted(
            ProxyTunnelTestResult("profile", ProxyTestStatus.AVAILABLE),
        )

        accumulator.reconcileReturned(
            listOf(ProxyTunnelTestResult("profile", ProxyTestStatus.NOT_TESTED)),
        )

        assertEquals(emptyList<ProxyTunnelTestResult>(), accumulator.snapshot())
    }

    @Test
    fun `caller stop cancellation is not converted into an internal deadline`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val operation = async {
            withMonotonicDeadlineOrNull(System.nanoTime() + 60_000_000_000L) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()

        operation.cancel(CancellationException("Stop"))

        try {
            operation.await()
            throw AssertionError("Caller cancellation was unexpectedly swallowed")
        } catch (_: CancellationException) {
            assertTrue(operation.isCancelled)
        }
    }

    private fun profile(
        name: String,
        latencyMs: Long?,
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
}
