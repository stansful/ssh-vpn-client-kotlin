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
 * newer command and stopSelfResult() refuses anyway.
 */
internal fun mayStopAfterAlwaysOnStart(
    state: VpnConnectionState,
    receiver: VpnSessionOwner,
    receiverRunActive: Boolean,
): Boolean {
    return !receiverRunActive && state.sessionOwner != receiver
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
