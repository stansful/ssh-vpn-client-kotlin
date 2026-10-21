package com.stansful.sshvpnclient.vpn

import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner

/** What Android's own start of the VPN (Always-on VPN, see [AlwaysOnVpnStarter]) does. */
internal sealed interface AlwaysOnStartDecision {
    /** A session is starting, running or stopping: Android's start must not restart or end it. */
    data class Ignore(val owner: VpnSessionOwner?, val status: VpnConnectionStatus) : AlwaysOnStartDecision

    /** Nothing was ever started on this install (fresh install, upgrade): there is no mode to restore. */
    data object NoRecordedMode : AlwaysOnStartDecision

    data class Start(val owner: VpnSessionOwner) : AlwaysOnStartDecision
}

/**
 * Unlike the tile, a missing record does not fall back to Server mode: nobody is looking at the phone
 * when Android starts the VPN, so only a mode the user actually ran is brought back.
 */
internal fun resolveAlwaysOnStart(
    state: VpnConnectionState,
    lastOwner: VpnSessionOwner?,
): AlwaysOnStartDecision {
    if (state.sessionOwner != null || state.status in ALWAYS_ON_BUSY_STATUSES) {
        return AlwaysOnStartDecision.Ignore(state.sessionOwner, state.status)
    }
    return lastOwner?.let(AlwaysOnStartDecision::Start) ?: AlwaysOnStartDecision.NoRecordedMode
}

/**
 * Whether the service Android started may stop itself for that start (stopSelfResult with its id) once
 * the start was handed on. Not while it runs a session itself: that session belongs to an earlier
 * command of the same service, and the start id Android just used is the newest one, so the stop would
 * go through and end it. When this start made the service run the mode, the use case already queued a
 * newer command and stopSelfResult() refuses anyway. A session state left behind by a run that ended
 * while this start was queued is not decided here: [RefusedSessionStop] settles it first.
 */
internal fun mayStopAfterAlwaysOnStart(
    state: VpnConnectionState,
    receiver: VpnSessionOwner,
    receiverRunActive: Boolean,
): Boolean {
    return !receiverRunActive && state.sessionOwner != receiver
}

/**
 * The start id a service passes to stopSelfResult() when its own session stops or fails. An Android
 * start the service kept running through ([mayStopAfterAlwaysOnStart] said no) is newer than the
 * session's command but needs no stop of its own, so it must not make that stop look superseded;
 * a command of the app queued after it still does, and the lifecycle command id still guards the
 * commands already delivered. Without this, a session that fails after such a start keeps its
 * "Connecting" state and notification forever.
 */
internal fun sessionStopStartId(sessionStartId: Int, keptAlwaysOnStartId: Int): Int {
    return maxOf(sessionStartId, keptAlwaysOnStartId)
}

/** What [RefusedSessionStop.settle] did for Android's start. */
internal enum class RefusedStopSettlement {
    /** No session end was waiting, or a newer command of the app has replaced it since. */
    NONE,

    /** The waiting end went through under Android's start id: its state is published, the notice gone. */
    STOPPED,

    /** A command newer than Android's start is queued as well; that one ends or restarts the session. */
    STILL_REFUSED,
}

/**
 * The end of a session whose stopSelfResult() was refused. A service stops before it publishes the
 * error or Disconnected, so a start Android has queued but not delivered yet makes the stop refuse and
 * leaves the end to that start. A command of the app replaces the session anyway; Android's Always-on
 * start does not, and without this the service would stay up, "Connecting" with its notice, behind a
 * run that has already ended. Android's start settles it instead: the same stop under its newer id.
 *
 * Main thread only, like the stops it holds.
 */
internal class RefusedSessionStop {
    private var pending: Pending? = null

    /**
     * [isCurrent] re-checks the guards of the refused stop (run, lifecycle command, ownership), so a
     * command of the app delivered meanwhile turns it stale; [finish] publishes the end and drops the
     * notice. A newer refused stop replaces an older one.
     */
    fun record(isCurrent: () -> Boolean, finish: () -> Unit) {
        pending = Pending(isCurrent, finish)
    }

    /** For Android's start, with [stopSelfResult] bound to that start's id. */
    fun settle(stopSelfResult: () -> Boolean): RefusedStopSettlement {
        val stop = pending ?: return RefusedStopSettlement.NONE
        if (!stop.isCurrent()) {
            pending = null
            return RefusedStopSettlement.NONE
        }
        if (!stopSelfResult()) return RefusedStopSettlement.STILL_REFUSED
        pending = null
        stop.finish()
        return RefusedStopSettlement.STOPPED
    }

    private class Pending(val isCurrent: () -> Boolean, val finish: () -> Unit)
}

/** Android's start arrived just after the receiver's own session ended; it settled that end instead. */
internal fun alwaysOnEndedSessionDiagnostic(receiver: VpnSessionOwner): String {
    return "Always-on VPN: Android's start ignored, ${receiver.alwaysOnModeLabel()} had just ended"
}

internal fun alwaysOnIgnoredDiagnostic(decision: AlwaysOnStartDecision.Ignore): String {
    val session = decision.owner?.alwaysOnModeLabel() ?: "a session"
    return "Always-on VPN: Android's start ignored, $session is already ${decision.status.alwaysOnLabel()}"
}

internal const val ALWAYS_ON_NO_RECORDED_MODE_DIAGNOSTIC =
    "Always-on VPN: no mode to restore yet; connect once from the app"

internal fun alwaysOnStartedDiagnostic(owner: VpnSessionOwner): String {
    return "Always-on VPN: ${owner.alwaysOnModeLabel()} started by Android"
}

/** The tile would open the app for [reason]; with nobody looking, the log says what to fix there. */
internal fun alwaysOnSkippedDiagnostic(owner: VpnSessionOwner, reason: QuickTileOpenAppReason): String {
    return "Always-on VPN: ${owner.alwaysOnModeLabel()} not started, ${reason.alwaysOnText()}; " +
        "open the app to fix it"
}

internal fun alwaysOnFailedDiagnostic(owner: VpnSessionOwner, message: String): String {
    return "Always-on VPN: ${owner.alwaysOnModeLabel()} failed to start: $message"
}

/** The mode names the app shows (R.string.mode_server, mode_routes, mode_auto). */
private fun VpnSessionOwner.alwaysOnModeLabel(): String = when (this) {
    VpnSessionOwner.SHADOW_SSH -> "Server"
    VpnSessionOwner.OPEN_SOURCE -> "Routes"
    VpnSessionOwner.SMART_CONNECT -> "Auto"
}

private fun VpnConnectionStatus.alwaysOnLabel(): String = when (this) {
    VpnConnectionStatus.CONNECTING -> "connecting"
    VpnConnectionStatus.CONNECTED -> "connected"
    VpnConnectionStatus.RECONNECTING -> "reconnecting"
    VpnConnectionStatus.DISCONNECTING -> "disconnecting"
    VpnConnectionStatus.DISCONNECTED -> "disconnected"
    VpnConnectionStatus.ERROR -> "failing"
}

private fun QuickTileOpenAppReason.alwaysOnText(): String = when (this) {
    QuickTileOpenAppReason.CONSENT_REQUIRED -> "its third-party notice is not accepted yet"
    QuickTileOpenAppReason.NO_SSH_CONFIG -> "no server selected"
    QuickTileOpenAppReason.SSH_KEY_MISSING -> "the selected SSH key is missing"
    QuickTileOpenAppReason.NO_PUBLIC_ROUTE -> "no public route selected"
    QuickTileOpenAppReason.XRAY_CORE_MISSING -> "the Xray engine is not installed"
    QuickTileOpenAppReason.NO_SELECTED_APPS -> "no apps selected for app routing"
    QuickTileOpenAppReason.VPN_PERMISSION_REQUIRED -> "VPN permission is not granted"
}

private val ALWAYS_ON_BUSY_STATUSES = setOf(
    VpnConnectionStatus.CONNECTING,
    VpnConnectionStatus.CONNECTED,
    VpnConnectionStatus.RECONNECTING,
    VpnConnectionStatus.DISCONNECTING,
)
