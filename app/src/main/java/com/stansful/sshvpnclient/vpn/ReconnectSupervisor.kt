package com.stansful.sshvpnclient.vpn

/**
 * Something that asks the SSH runtime to look at its transport. Android types stay in the service;
 * this is only what the reconnect policy needs to decide.
 */
internal sealed interface ReconnectTrigger {
    val source: String

    /** The link watchdog condemned the transport of link [linkId]; its socket is already closed. */
    data class TransportDead(val linkId: Long, val detail: String) : ReconnectTrigger {
        override val source: String get() = "link watchdog"
    }

    /** An app needed the tunnel while no SSH transport existed (a SYN or DNS query was held back). */
    data object TransportDemand : ReconnectTrigger {
        override val source: String get() = "traffic without transport"
    }

    /**
     * Silence from SSH while apps kept sending; [active] false means the episode cleared. [linkId] is
     * the link that was active when the forwarder said so: a report can outlive its link in the queue.
     */
    data class DataPathStall(val active: Boolean, val linkId: Long) : ReconnectTrigger {
        override val source: String get() = "TUN stall watchdog"
    }

    data class DeviceWake(val screenOffMs: Long?) : ReconnectTrigger {
        override val source: String get() = "screen on"
    }

    /**
     * The physical network under the transport changed in place: new addresses, a validation flip,
     * traffic unblocked. [addressLostByLink] is the id of the link whose socket address went away
     * with this change, if one did. [degraded]: the network got worse (lost validation) - worth a
     * probe of a live link, no reason to cut a backoff short.
     */
    data class NetworkRefresh(
        val detail: String,
        val addressLostByLink: Long? = null,
        val degraded: Boolean = false,
    ) : ReconnectTrigger {
        override val source: String get() = "network"
    }

    /** A different physical network was selected; the network callback has already dropped the old transport. */
    data class NetworkHandoff(val detail: String) : ReconnectTrigger {
        override val source: String get() = "network handoff"
    }

    /** The allow-while-idle alarm that stands in for a backoff timer frozen by deep sleep. */
    data object RetryAlarm : ReconnectTrigger {
        override val source: String get() = "retry alarm"
    }
}

internal sealed interface ReconnectDecision {
    val summary: String

    data class Ignore(val reason: String) : ReconnectDecision {
        override val summary: String get() = "ignored: $reason"
    }

    data class Probe(val reason: String) : ReconnectDecision {
        override val summary: String get() = "probe: $reason"
    }

    data class Interrupt(val reason: String, val forceVpnRebuild: Boolean) : ReconnectDecision {
        override val summary: String
            get() = if (forceVpnRebuild) "rebuild VPN: $reason" else "reconnect SSH: $reason"
    }

    /** Leave the backoff wait; [afterMs] keeps a minimum spacing between attempts. */
    data class RetryNow(val reason: String, val afterMs: Long) : ReconnectDecision {
        override val summary: String
            get() = if (afterMs > 0L) "retry in ${afterMs}ms: $reason" else "retry now: $reason"
    }
}

internal data class ConnectionLossPlan(
    /** Reconnect without waiting: the lost session had been stable. */
    val immediate: Boolean,
    val forceVpnRebuild: Boolean,
    /** Extra context for the log, e.g. a detected flapping streak. */
    val note: String?,
)

/**
 * The one owner of reconnect decisions. The service's connection loop is the only caller and the
 * only place that starts attempts; everything else - watchdog verdicts, network callbacks, screen
 * events, forwarder hints - arrives here as a [ReconnectTrigger] and gets a decision back.
 *
 * Pure and single-threaded: state plus explicit times in, decisions out. Stale triggers are
 * dropped by link generation, so a late verdict about link #6 can never tear down link #7.
 *
 * Degradation ladder for a data path that stays stalled while the link answers keepalives:
 * probe first, then a hot SSH reconnect, and a full VPN rebuild only if the stall comes back soon
 * after that. A transport that keeps dying young gets one rebuild per streak as well.
 *
 * With the screen off the backoff keeps growing past the interactive cap: every retry there costs
 * a wake-up, and an unreachable server must not turn into a wake-up every 30 s all night. An app
 * that asks for the tunnel still shortens the wait, but only to half of it (15 s at most).
 */
internal class ReconnectSupervisor(
    private val initialBackoffMs: Long = 250L,
    private val maxBackoffMs: Long = 30_000L,
    private val screenOffMaxBackoffMs: Long = 5 * 60_000L,
    private val stableConnectionMs: Long = 30_000L,
    private val retrySpacingMs: Long = 1_000L,
    private val demandSpacingCapMs: Long = 15_000L,
    private val flappingStreakForRebuild: Int = 3,
    private val stallEscalationMs: Long = 60_000L,
    private val stallRecurrenceWindowMs: Long = 10 * 60_000L,
) {
    enum class Phase { IDLE, CONNECTING, CONNECTED, BACKOFF }

    private val interactiveBackoff = ReconnectBackoff(initialBackoffMs, maxBackoffMs)
    private val screenOffBackoff = ReconnectBackoff(initialBackoffMs, maxOf(maxBackoffMs, screenOffMaxBackoffMs))
    private var currentBackoffDelayMs = 0L
    private var backoffStartedAtMs = NEVER

    var phase: Phase = Phase.IDLE
        private set

    /** Id of the link the current session runs on; 0 before the first connection. */
    var generation: Long = 0L
        private set

    private var lastAttemptStartedAtMs = NEVER
    private var lastAttemptEndedAtMs = NEVER
    private var connectedAtMs = NEVER
    private var shortLivedStreak = 0
    private var rebuiltForCurrentStreak = false
    /** Awake time: a stall cannot be watched while the CPU sleeps, and sleep must not count towards it. */
    private var stallStartedAtAwakeMs = NEVER
    private var lastStallEscalationAtMs = NEVER

    fun onAttemptStarted(nowMs: Long) {
        phase = Phase.CONNECTING
        lastAttemptStartedAtMs = nowMs
    }

    /** The attempt returned or threw. Triggers posted up to here were already covered by it. */
    fun onAttemptEnded(nowMs: Long) {
        lastAttemptEndedAtMs = nowMs
    }

    fun onConnected(linkId: Long, nowMs: Long) {
        phase = Phase.CONNECTED
        generation = linkId
        connectedAtMs = nowMs
        stallStartedAtAwakeMs = NEVER
    }

    /** The monitored session ended. Decides whether to wait and whether the VPN must be rebuilt. */
    fun onConnectionLost(nowMs: Long, interruptForcesRebuild: Boolean): ConnectionLossPlan {
        val livedMs = if (connectedAtMs == NEVER) 0L else nowMs - connectedAtMs
        val stable = shouldResetReconnectBackoff(livedMs, stableConnectionMs)
        connectedAtMs = NEVER
        stallStartedAtAwakeMs = NEVER
        if (stable) {
            interactiveBackoff.reset()
            screenOffBackoff.reset()
            shortLivedStreak = 0
            rebuiltForCurrentStreak = false
        } else {
            shortLivedStreak += 1
        }
        val flapping = shortLivedStreak >= flappingStreakForRebuild
        val rebuildForFlapping = flapping && !rebuiltForCurrentStreak
        if (rebuildForFlapping) rebuiltForCurrentStreak = true
        val note = if (flapping) {
            "$shortLivedStreak sessions in a row lived less than ${stableConnectionMs / 1_000}s" +
                if (rebuildForFlapping) "; rebuilding the VPN interface once for this streak" else ""
        } else {
            null
        }
        return ConnectionLossPlan(
            immediate = stable,
            forceVpnRebuild = interruptForcesRebuild || rebuildForFlapping,
            note = note,
        )
    }

    /** Enters backoff and returns how long to wait before the next attempt. */
    fun beginBackoff(nowMs: Long, immediate: Boolean, deviceInteractive: Boolean = true): Long {
        phase = Phase.BACKOFF
        backoffStartedAtMs = nowMs
        if (immediate) {
            currentBackoffDelayMs = 0L
            return 0L
        }
        // Both sequences advance together, so a screen that turns on or off mid-streak picks up
        // the matching delay for the same number of failures.
        val interactiveDelayMs = interactiveBackoff.nextFailureDelayMs()
        val screenOffDelayMs = screenOffBackoff.nextFailureDelayMs()
        currentBackoffDelayMs = if (deviceInteractive) interactiveDelayMs else screenOffDelayMs
        return currentBackoffDelayMs
    }

    /**
     * @param nowMs elapsed real time, like every other time here except [awakeMs].
     * @param postedAtMs when the trigger was queued. One that waited in the queue through the last
     * attempt was already covered by it: a wake-up or an app's SYN from before its end is no reason
     * to cut the next wait short. A changed network still is.
     * @param awakeMs uptime, for the stall ladder only.
     */
    fun onTrigger(
        trigger: ReconnectTrigger,
        nowMs: Long,
        postedAtMs: Long = nowMs,
        awakeMs: Long = nowMs,
    ): ReconnectDecision {
        return when (phase) {
            Phase.CONNECTED -> onTriggerWhileConnected(trigger, awakeMs)
            Phase.BACKOFF -> if (postedAtMs <= lastAttemptEndedAtMs && !trigger.isNetworkChange()) {
                ReconnectDecision.Ignore("queued before the last attempt ended")
            } else {
                onTriggerWhileWaiting(trigger, nowMs)
            }
            Phase.CONNECTING -> ReconnectDecision.Ignore("a connect attempt is already running")
            Phase.IDLE -> ReconnectDecision.Ignore("not connecting")
        }
    }

    private fun ReconnectTrigger.isNetworkChange(): Boolean {
        return this is ReconnectTrigger.NetworkRefresh || this is ReconnectTrigger.NetworkHandoff
    }

    /**
     * Called on every monitor wake-up while connected: escalates a stall that outlived its probes.
     * The stall is measured in [awakeMs]: a phone that slept through the minute has not watched it.
     */
    fun onMonitorTick(nowMs: Long, awakeMs: Long = nowMs): ReconnectDecision? {
        if (phase != Phase.CONNECTED || stallStartedAtAwakeMs == NEVER) return null
        val stalledForMs = awakeMs - stallStartedAtAwakeMs
        if (stalledForMs < stallEscalationMs) return null
        val recurred = lastStallEscalationAtMs != NEVER && nowMs - lastStallEscalationAtMs < stallRecurrenceWindowMs
        lastStallEscalationAtMs = nowMs
        stallStartedAtAwakeMs = NEVER
        val reason = "data path stalled for ${stalledForMs / 1_000}s while the SSH link answered" +
            if (recurred) "; it came back after the last reconnect" else ""
        return ReconnectDecision.Interrupt(reason, forceVpnRebuild = recurred)
    }

    private fun onTriggerWhileConnected(trigger: ReconnectTrigger, awakeMs: Long): ReconnectDecision {
        return when (trigger) {
            is ReconnectTrigger.TransportDead -> if (trigger.linkId == generation) {
                ReconnectDecision.Interrupt("SSH link #${trigger.linkId} dead: ${trigger.detail}", false)
            } else {
                ReconnectDecision.Ignore("stale verdict for link #${trigger.linkId}; current link is #$generation")
            }
            ReconnectTrigger.TransportDemand ->
                ReconnectDecision.Ignore("the monitor re-checks the current transport now")
            is ReconnectTrigger.DataPathStall -> when {
                trigger.linkId != generation ->
                    ReconnectDecision.Ignore("stall report about link #${trigger.linkId}; current link is #$generation")
                trigger.active -> {
                    if (stallStartedAtAwakeMs == NEVER) stallStartedAtAwakeMs = awakeMs
                    ReconnectDecision.Probe("apps are sending but nothing comes back")
                }
                else -> {
                    stallStartedAtAwakeMs = NEVER
                    ReconnectDecision.Ignore("stall cleared")
                }
            }
            is ReconnectTrigger.DeviceWake -> ReconnectDecision.Probe(
                trigger.screenOffMs?.let { "screen was off for ${it / 1_000}s" } ?: "device woke",
            )
            is ReconnectTrigger.NetworkRefresh -> when (trigger.addressLostByLink) {
                null -> ReconnectDecision.Probe(trigger.detail)
                generation -> ReconnectDecision.Interrupt(
                    reason = "the SSH socket's local address left the network (${trigger.detail})",
                    forceVpnRebuild = false,
                )
                // The address belonged to an earlier link; the current socket was opened after the change.
                else -> ReconnectDecision.Probe(
                    "${trigger.detail}; the lost address was link #${trigger.addressLostByLink}'s",
                )
            }
            is ReconnectTrigger.NetworkHandoff ->
                ReconnectDecision.Ignore("the handoff already replaced the transport")
            ReconnectTrigger.RetryAlarm -> ReconnectDecision.Ignore("already connected")
        }
    }

    private fun onTriggerWhileWaiting(trigger: ReconnectTrigger, nowMs: Long): ReconnectDecision {
        val reason = when (trigger) {
            is ReconnectTrigger.TransportDead -> return ReconnectDecision.Ignore("already reconnecting")
            is ReconnectTrigger.DataPathStall -> return ReconnectDecision.Ignore("already reconnecting")
            ReconnectTrigger.TransportDemand -> "an app is waiting for the tunnel"
            is ReconnectTrigger.DeviceWake -> "the device woke up"
            is ReconnectTrigger.NetworkRefresh -> if (trigger.degraded) {
                return ReconnectDecision.Ignore("${trigger.detail}: no reason to retry sooner")
            } else {
                "network changed: ${trigger.detail}"
            }
            is ReconnectTrigger.NetworkHandoff -> "new network: ${trigger.detail}"
            ReconnectTrigger.RetryAlarm -> "the backoff timer was frozen by deep sleep"
        }
        val sinceAttemptMs = if (lastAttemptStartedAtMs == NEVER) Long.MAX_VALUE else nowMs - lastAttemptStartedAtMs
        var afterMs = (retrySpacingMs - sinceAttemptMs).coerceAtLeast(0L)
        if (trigger == ReconnectTrigger.TransportDemand) {
            // Apps retry on their own schedule: a server that stays down must not be hammered, and a
            // session that keeps dying young must not be revived by every SYN. Half the wait, at most 15 s.
            val demandWaitMs = minOf(currentBackoffDelayMs / 2, demandSpacingCapMs)
            afterMs = maxOf(afterMs, demandWaitMs - (nowMs - backoffStartedAtMs))
        }
        return ReconnectDecision.RetryNow(reason, afterMs)
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}

/**
 * Keeps trigger logging honest without flooding the 500-line diagnostics buffer: every distinct
 * (source, decision) pair is logged, a repeat of the same pair at most once per [windowMs], and
 * the next line that does get through says how many were folded into it.
 */
internal class TriggerLogThrottle(private val windowMs: Long = 60_000L) {
    private val lastLoggedAtMs = HashMap<String, Long>()
    private val suppressed = HashMap<String, Int>()

    @Synchronized
    fun filter(key: String, line: String, nowMs: Long): String? {
        val last = lastLoggedAtMs[key]
        if (last != null && nowMs - last < windowMs) {
            suppressed[key] = (suppressed[key] ?: 0) + 1
            return null
        }
        lastLoggedAtMs[key] = nowMs
        val folded = suppressed.remove(key) ?: 0
        if (lastLoggedAtMs.size > MAX_KEYS) {
            lastLoggedAtMs.keys.retainAll(setOf(key))
            suppressed.keys.retainAll(setOf(key))
        }
        return if (folded > 0) "$line (+$folded similar)" else line
    }

    private companion object {
        const val MAX_KEYS = 64
    }
}
