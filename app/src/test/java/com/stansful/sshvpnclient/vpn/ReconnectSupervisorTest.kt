package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectSupervisorTest {
    private var now = 1_000_000L

    private fun supervisor() = ReconnectSupervisor(
        initialBackoffMs = 250L,
        maxBackoffMs = 30_000L,
        stableConnectionMs = 30_000L,
        retrySpacingMs = 1_000L,
        flappingStreakForRebuild = 3,
        stallEscalationMs = 60_000L,
        stallRecurrenceWindowMs = 600_000L,
    )

    private fun ReconnectSupervisor.connect(linkId: Long) {
        onAttemptStarted(now)
        onAttemptEnded(now)
        onConnected(linkId, now)
    }

    private fun ReconnectSupervisor.failAttempt(durationMs: Long = 0L, deviceInteractive: Boolean = true): Long {
        onAttemptStarted(now)
        now += durationMs
        onAttemptEnded(now)
        return beginBackoff(now, immediate = false, deviceInteractive = deviceInteractive)
    }

    @Test
    fun `failed attempts back off exponentially up to the cap`() {
        val supervisor = supervisor()
        val delays = (1..9).map {
            supervisor.onAttemptStarted(now)
            supervisor.beginBackoff(now, immediate = false)
        }
        assertEquals(listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)
        assertEquals(ReconnectSupervisor.Phase.BACKOFF, supervisor.phase)
    }

    @Test
    fun `with the screen off the backoff grows past the interactive cap`() {
        val supervisor = supervisor()
        val delays = (1..12).map {
            supervisor.onAttemptStarted(now)
            supervisor.beginBackoff(now, immediate = false, deviceInteractive = false)
        }
        assertEquals(
            listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 128_000L, 256_000L, 300_000L),
            delays,
        )
        supervisor.onAttemptStarted(now)
        val screenOn = supervisor.beginBackoff(now, immediate = false, deviceInteractive = true)
        assertEquals("screen on: the interactive sequence, same streak", 30_000L, screenOn)
    }

    @Test
    fun `an app asking for the tunnel shortens a long wait only to half of it`() {
        val supervisor = supervisor()
        repeat(11) {
            supervisor.onAttemptStarted(now)
            supervisor.beginBackoff(now, immediate = false, deviceInteractive = false)
        }
        now += 2_000L
        val demand = supervisor.onTrigger(ReconnectTrigger.TransportDemand, now) as ReconnectDecision.RetryNow
        assertEquals("capped at 15 s after the last attempt", 13_000L, demand.afterMs)
        val wake = supervisor.onTrigger(ReconnectTrigger.DeviceWake(null), now) as ReconnectDecision.RetryNow
        assertEquals("the user is here: retry now", 0L, wake.afterMs)
    }

    @Test
    fun `losing a stable session reconnects at once and resets the backoff`() {
        val supervisor = supervisor()
        repeat(4) {
            supervisor.onAttemptStarted(now)
            supervisor.beginBackoff(now, immediate = false)
        }
        supervisor.connect(linkId = 7)
        now += 31_000L
        val plan = supervisor.onConnectionLost(now, interruptForcesRebuild = false)
        assertTrue(plan.immediate)
        assertFalse(plan.forceVpnRebuild)
        assertNull(plan.note)
        assertEquals(0L, supervisor.beginBackoff(now, plan.immediate))
        supervisor.onAttemptStarted(now)
        assertEquals("backoff restarts from the initial delay", 250L, supervisor.beginBackoff(now, immediate = false))
    }

    @Test
    fun `a flapping transport gets one VPN rebuild per streak`() {
        val supervisor = supervisor()
        val plans = (1L..5L).map { linkId ->
            supervisor.connect(linkId)
            now += 5_000L
            supervisor.onConnectionLost(now, interruptForcesRebuild = false)
        }
        assertEquals(listOf(false, false, true, false, false), plans.map { it.forceVpnRebuild })
        assertTrue(plans.all { !it.immediate })
        assertTrue(plans[2].note!!.contains("3 sessions in a row"))

        supervisor.connect(linkId = 6)
        now += 40_000L
        val stable = supervisor.onConnectionLost(now, interruptForcesRebuild = false)
        assertTrue(stable.immediate)
        (7L..9L).forEach { linkId ->
            supervisor.connect(linkId)
            now += 5_000L
            val plan = supervisor.onConnectionLost(now, interruptForcesRebuild = false)
            assertEquals("a new streak earns a new rebuild", linkId == 9L, plan.forceVpnRebuild)
        }
    }

    @Test
    fun `verdicts about an older link are dropped`() {
        val supervisor = supervisor()
        supervisor.connect(linkId = 7)
        val stale = supervisor.onTrigger(ReconnectTrigger.TransportDead(linkId = 6, detail = "PROBE_TIMEOUT"), now)
        assertTrue(stale is ReconnectDecision.Ignore)
        assertTrue(stale.summary.contains("link #6"))

        val current = supervisor.onTrigger(ReconnectTrigger.TransportDead(linkId = 7, detail = "PROBE_TIMEOUT"), now)
        assertEquals(ReconnectDecision.Interrupt("SSH link #7 dead: PROBE_TIMEOUT", false), current)
    }

    @Test
    fun `while connected, hints turn into probes and address loss into a reconnect`() {
        val supervisor = supervisor()
        supervisor.connect(linkId = 3)
        val wake = supervisor.onTrigger(ReconnectTrigger.DeviceWake(screenOffMs = 600_000L), now)
        assertEquals(ReconnectDecision.Probe("screen was off for 600s"), wake)
        assertTrue(
            supervisor.onTrigger(ReconnectTrigger.NetworkRefresh("validated"), now) is ReconnectDecision.Probe,
        )
        assertTrue(
            supervisor.onTrigger(ReconnectTrigger.NetworkRefresh("-1 address", addressLostByLink = 3), now)
                is ReconnectDecision.Interrupt,
        )
        assertTrue(supervisor.onTrigger(ReconnectTrigger.TransportDemand, now) is ReconnectDecision.Ignore)
        assertTrue(supervisor.onTrigger(ReconnectTrigger.RetryAlarm, now) is ReconnectDecision.Ignore)
        assertTrue(supervisor.onTrigger(ReconnectTrigger.NetworkHandoff("a -> b"), now) is ReconnectDecision.Ignore)
    }

    @Test
    fun `backoff is cut short by demand, wake-up and network, never closer than the spacing`() {
        val supervisor = supervisor()
        supervisor.onAttemptStarted(now)
        supervisor.beginBackoff(now, immediate = false)

        now += 300L
        val early = supervisor.onTrigger(ReconnectTrigger.TransportDemand, now)
        assertEquals(ReconnectDecision.RetryNow("an app is waiting for the tunnel", afterMs = 700L), early)

        now += 5_000L
        listOf(
            ReconnectTrigger.DeviceWake(screenOffMs = null),
            ReconnectTrigger.NetworkRefresh("network validated"),
            ReconnectTrigger.NetworkHandoff("wifi is available"),
            ReconnectTrigger.RetryAlarm,
        ).forEach { trigger ->
            val decision = supervisor.onTrigger(trigger, now)
            assertTrue("$trigger", decision is ReconnectDecision.RetryNow && decision.afterMs == 0L)
        }
        assertTrue(
            supervisor.onTrigger(ReconnectTrigger.TransportDead(1, "late"), now) is ReconnectDecision.Ignore,
        )
        val stall = supervisor.onTrigger(ReconnectTrigger.DataPathStall(active = true, linkId = 0), now)
        assertTrue(stall is ReconnectDecision.Ignore)
    }

    @Test
    fun `a session that keeps dying young is not revived by every SYN`() {
        val supervisor = supervisor()
        var delayMs = 0L
        (1L..6L).forEach { linkId ->
            supervisor.connect(linkId)
            now += 5_000L
            val plan = supervisor.onConnectionLost(now, interruptForcesRebuild = false)
            delayMs = supervisor.beginBackoff(now, plan.immediate)
        }
        assertEquals(8_000L, delayMs)
        now += 100L
        val demand = supervisor.onTrigger(ReconnectTrigger.TransportDemand, now) as ReconnectDecision.RetryNow
        assertEquals("half of the wait, counted from when it began", 3_900L, demand.afterMs)
    }

    @Test
    fun `a network that got worse is probed while connected but does not cut a backoff short`() {
        val supervisor = supervisor()
        supervisor.connect(linkId = 1)
        val lost = ReconnectTrigger.NetworkRefresh("network lost validation", degraded = true)
        assertTrue(supervisor.onTrigger(lost, now) is ReconnectDecision.Probe)
        supervisor.onConnectionLost(now, interruptForcesRebuild = false)
        supervisor.beginBackoff(now, immediate = false)
        now += 2_000L
        assertTrue(supervisor.onTrigger(lost, now) is ReconnectDecision.Ignore)
    }

    @Test
    fun `hints that queued up before the failed attempt ended do not skip the backoff`() {
        val supervisor = supervisor()
        supervisor.onAttemptStarted(now)
        val postedDuringAttempt = now + 3_000L
        now += 16_000L
        supervisor.onAttemptEnded(now)
        supervisor.beginBackoff(now, immediate = false)
        now += 50L
        val queued = listOf(
            ReconnectTrigger.DeviceWake(null),
            ReconnectTrigger.TransportDemand,
            ReconnectTrigger.RetryAlarm,
        )
        queued.forEach {
            val decision = supervisor.onTrigger(it, now, postedAtMs = postedDuringAttempt)
            assertEquals(ReconnectDecision.Ignore("queued before the last attempt ended"), decision)
        }
        val network = supervisor.onTrigger(
            ReconnectTrigger.NetworkHandoff("wifi -> cellular"),
            now,
            postedAtMs = postedDuringAttempt,
        )
        assertTrue("a changed network is still worth an early retry", network is ReconnectDecision.RetryNow)
    }

    @Test
    fun `a wake-up right after a failed attempt still cuts a long screen-off wait short`() {
        val supervisor = supervisor()
        repeat(11) { supervisor.failAttempt(durationMs = 16_000L, deviceInteractive = false) }
        val attemptEndedAt = now
        now += 200L
        val wake = supervisor.onTrigger(ReconnectTrigger.DeviceWake(5_000L), now, postedAtMs = attemptEndedAt + 100L)
        assertEquals(ReconnectDecision.RetryNow("the device woke up", afterMs = 0L), wake)
    }

    @Test
    fun `nothing interrupts a running attempt`() {
        val supervisor = supervisor()
        supervisor.onAttemptStarted(now)
        assertTrue(supervisor.onTrigger(ReconnectTrigger.DeviceWake(null), now) is ReconnectDecision.Ignore)
        assertTrue(supervisor.onTrigger(ReconnectTrigger.TransportDemand, now) is ReconnectDecision.Ignore)
    }

    @Test
    fun `a stall that outlives its probes climbs the ladder`() {
        val supervisor = supervisor()
        supervisor.connect(linkId = 1)
        val probe = supervisor.onTrigger(ReconnectTrigger.DataPathStall(active = true, linkId = 1), now)
        assertTrue(probe is ReconnectDecision.Probe)
        now += 59_000L
        assertNull(supervisor.onMonitorTick(now))
        now += 1_000L
        val first = supervisor.onMonitorTick(now) as ReconnectDecision.Interrupt
        assertFalse("first rung is a hot SSH reconnect", first.forceVpnRebuild)
        assertNull("one escalation per stall episode", supervisor.onMonitorTick(now + 1_000L))

        supervisor.onConnectionLost(now, interruptForcesRebuild = false)
        supervisor.connect(linkId = 2)
        supervisor.onTrigger(ReconnectTrigger.DataPathStall(active = true, linkId = 2), now)
        now += 60_000L
        val second = supervisor.onMonitorTick(now) as ReconnectDecision.Interrupt
        assertTrue("the stall came back soon after: rebuild the VPN", second.forceVpnRebuild)
    }

    @Test
    fun `deep sleep does not count as stall time`() {
        val supervisor = supervisor()
        supervisor.connect(linkId = 1)
        var awake = 5_000L
        supervisor.onTrigger(ReconnectTrigger.DataPathStall(active = true, linkId = 1), now, awakeMs = awake)
        now += 600_000L
        awake += 1_000L
        assertNull("ten minutes asleep, one second watched", supervisor.onMonitorTick(now, awake))
        now += 59_000L
        awake += 59_000L
        val escalation = supervisor.onMonitorTick(now, awake) as ReconnectDecision.Interrupt
        assertFalse(escalation.forceVpnRebuild)
    }

    @Test
    fun `a cleared stall does not escalate`() {
        val supervisor = supervisor()
        supervisor.connect(linkId = 1)
        supervisor.onTrigger(ReconnectTrigger.DataPathStall(active = true, linkId = 1), now)
        now += 10_000L
        val cleared = supervisor.onTrigger(ReconnectTrigger.DataPathStall(active = false, linkId = 1), now)
        assertTrue(cleared is ReconnectDecision.Ignore)
        now += 120_000L
        assertNull(supervisor.onMonitorTick(now))
    }

    @Test
    fun `stall reports and address losses about an earlier link leave the current one alone`() {
        val supervisor = supervisor()
        supervisor.connect(linkId = 4)
        supervisor.onConnectionLost(now, interruptForcesRebuild = false)
        supervisor.connect(linkId = 5)

        val staleStall = supervisor.onTrigger(ReconnectTrigger.DataPathStall(active = true, linkId = 4), now)
        assertTrue(staleStall is ReconnectDecision.Ignore)
        now += 120_000L
        assertNull("a stale report must not start the ladder", supervisor.onMonitorTick(now))

        val staleLoss = supervisor.onTrigger(ReconnectTrigger.NetworkRefresh("-1 address", addressLostByLink = 4), now)
        assertTrue("the network did change: probe, but do not drop link #5", staleLoss is ReconnectDecision.Probe)
    }

    @Test
    fun `trigger log folds repeats inside the window`() {
        val throttle = TriggerLogThrottle(windowMs = 60_000L)
        assertEquals("a", throttle.filter("screen on|Probe", "a", 0L))
        assertNull(throttle.filter("screen on|Probe", "b", 10_000L))
        assertNull(throttle.filter("screen on|Probe", "c", 20_000L))
        assertEquals("other keys are independent", "x", throttle.filter("network|Probe", "x", 20_000L))
        assertEquals("d (+2 similar)", throttle.filter("screen on|Probe", "d", 60_000L))
        assertEquals("e", throttle.filter("screen on|Probe", "e", 120_000L))
    }
}
