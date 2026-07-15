package com.stansful.sshvpnclient.ui.opensource

import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedParallelMapTest {
    @Test
    fun `bounded map preserves order and caps active transforms`() = runBlocking {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val inputs = (0 until 41).toList()

        val results = mapConcurrentOrdered(
            values = inputs,
            maxConcurrency = 4,
        ) { value ->
            val activeNow = active.incrementAndGet()
            maximumActive.updateAndGet { previous -> maxOf(previous, activeNow) }
            try {
                delay((5 - value % 4).toLong())
                value * 2
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals(inputs.map { it * 2 }, results)
        assertEquals(4, maximumActive.get())
        assertTrue(active.get() == 0)
    }

    @Test
    fun `result callback is emitted before a slow worker peer finishes`() = runBlocking {
        val releaseSlowTransform = CompletableDeferred<Unit>()
        val fastResultReported = CompletableDeferred<Unit>()

        val mapped = async {
            mapConcurrentOrdered(
                values = listOf(0, 1),
                maxConcurrency = 2,
                onResult = { _, result ->
                    if (result == 10) fastResultReported.complete(Unit)
                },
            ) { value ->
                if (value == 0) releaseSlowTransform.await()
                value * 10
            }
        }

        withTimeout(1_000) { fastResultReported.await() }
        assertFalse(mapped.isCompleted)
        releaseSlowTransform.complete(Unit)
        assertEquals(listOf(0, 10), mapped.await())
    }

    @Test
    fun `free worker starts next input without waiting for slow peer`() = runBlocking {
        val releaseFirst = CompletableDeferred<Unit>()
        val thirdStarted = CompletableDeferred<Unit>()

        val mapped = async {
            mapConcurrentOrdered(
                values = listOf(0, 1, 2),
                maxConcurrency = 2,
            ) { value ->
                if (value == 0) releaseFirst.await()
                if (value == 2) thirdStarted.complete(Unit)
                value
            }
        }

        withTimeout(1_000) { thirdStarted.await() }
        assertFalse(mapped.isCompleted)
        releaseFirst.complete(Unit)
        assertEquals(listOf(0, 1, 2), mapped.await())
    }

    @Test
    fun `353 item progress is live without flooding UI updates`() {
        val gate = CheckProgressPublicationGate(total = 353) { 0L }
        val publications = (1..353).filter(gate::shouldPublish)

        assertTrue(publications.size <= 101)
        assertEquals(353, publications.last())
    }

    @Test
    fun `large progress stays bounded but slow completion breaks silence`() {
        assertEquals(1, checkProgressPublishStride(1))
        assertEquals(1, checkProgressPublishStride(100))
        assertEquals(2, checkProgressPublishStride(101))
        assertEquals(10, checkProgressPublishStride(1_000))
        assertEquals(100, checkProgressPublishStride(10_000))

        val total = 10_001
        val fastGate = CheckProgressPublicationGate(total) { 0L }
        val publications = (1..total).filter(fastGate::shouldPublish)

        assertTrue(publications.size <= 101)
        assertEquals(total, publications.last())

        var nowNanos = 0L
        val slowGate = CheckProgressPublicationGate(total) { nowNanos }
        assertFalse(slowGate.shouldPublish(1))
        nowNanos = 100_000_000L
        assertTrue(slowGate.shouldPublish(2))
    }

    @Test
    fun `unreachable endpoints are discarded first and reachable ones use latency order`() {
        val prioritized = prioritizeTunnelChecks(
            profileIds = listOf("fast", "dead-a", "slow", "dead-b"),
            endpointLatencies = mapOf("fast" to 20L, "slow" to 350L),
            endpointUnavailableIds = setOf("dead-a", "dead-b"),
        )

        assertEquals(listOf("dead-a", "dead-b", "fast", "slow"), prioritized)
    }

    @Test
    fun `failed TCP ping does not discard UDP based transports`() {
        assertFalse(ProxyTransport.MKCP.tcpPingCanRejectTunnel())
        assertFalse(ProxyTransport.HYSTERIA.tcpPingCanRejectTunnel())
        assertTrue(ProxyTransport.RAW.tcpPingCanRejectTunnel())
        assertTrue(ProxyTransport.GRPC.tcpPingCanRejectTunnel())
    }

    @Test
    fun `only numeric IP ping failure can reject a TCP tunnel`() {
        assertTrue("203.0.113.7".isNumericIpLiteral())
        assertTrue("[2001:db8::1]".isNumericIpLiteral())
        assertTrue("fe80::1%wlan0".isNumericIpLiteral())
        assertFalse("proxy.example.com".isNumericIpLiteral())
        assertFalse("999.1.1.1".isNumericIpLiteral())
    }

    @Test
    fun `progress text distinguishes ping and tunnel phase from overall work`() {
        val ping = OpenSourceUiState(
            isChecking = true,
            checkCompleted = 12,
            checkTotal = 706,
            checkPhase = ProxyCheckPhase.PING_ENDPOINTS,
            checkPhaseCompleted = 12,
            checkPhaseTotal = 353,
        )
        val tunnel = ping.copy(
            checkCompleted = 357,
            checkPhase = ProxyCheckPhase.TUNNELS,
            checkPhaseCompleted = 4,
        )

        assertEquals("Pinging endpoints 12/353 · overall 12/706", ping.checkProgressText)
        assertEquals("Checking tunnels 4/353 · overall 357/706", tunnel.checkProgressText)
    }

    @Test
    fun `unavailable cleanup is enabled only while bulk operations are idle`() {
        val ready = OpenSourceUiState(unavailableUnpinnedCount = 3)

        assertTrue(ready.canRemoveUnavailable)
        assertFalse(ready.copy(unavailableUnpinnedCount = 0).canRemoveUnavailable)
        assertFalse(ready.copy(isChecking = true).canRemoveUnavailable)
        assertFalse(ready.copy(isSyncing = true).canRemoveUnavailable)
        assertFalse(ready.copy(isRemovingUnavailable = true).canRemoveUnavailable)
        assertFalse(
            ready.copy(
                vpnState = VpnConnectionState(
                    status = VpnConnectionStatus.CONNECTED,
                    activeTransport = VpnTransportType.XRAY,
                    sessionOwner = VpnSessionOwner.OPEN_SOURCE,
                ),
            ).canRemoveUnavailable,
        )
    }

    @Test
    fun `unavailable cleanup result message handles zero singular and plural`() {
        assertEquals("No unavailable tunnels to remove", removedUnavailableMessage(0))
        assertEquals("Removed 1 unavailable tunnel", removedUnavailableMessage(1))
        assertEquals("Removed 12 unavailable tunnels", removedUnavailableMessage(12))
    }
}
