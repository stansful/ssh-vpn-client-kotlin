package com.stansful.sshvpnclient.vpn

import androidx.annotation.DrawableRes
import com.stansful.sshvpnclient.R
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectVpnResult

/**
 * What the quick settings tile found out about the mode it is about to start. Android lookups
 * stay in the tile service; this is only what the dispatch decision needs.
 */
internal sealed interface QuickTilePreconditions {
    val selectedAppsReady: Boolean
    val vpnPermissionGranted: Boolean

    data class ShadowSsh(
        val configSelected: Boolean,
        /** True for password configs; for key configs, the referenced key still exists. */
        val privateKeyReady: Boolean,
        override val selectedAppsReady: Boolean,
        override val vpnPermissionGranted: Boolean,
    ) : QuickTilePreconditions

    data class OpenSource(
        val consentAccepted: Boolean,
        val routeSelected: Boolean,
        val xrayCoreAvailable: Boolean,
        override val selectedAppsReady: Boolean,
        override val vpnPermissionGranted: Boolean,
    ) : QuickTilePreconditions

    data class SmartConnect(
        val consentAccepted: Boolean,
        val xrayCoreAvailable: Boolean,
        override val selectedAppsReady: Boolean,
        override val vpnPermissionGranted: Boolean,
    ) : QuickTilePreconditions
}

internal enum class QuickTileOpenAppReason {
    /** Not an error: the tab's consent dialog takes over once it is shown. */
    CONSENT_REQUIRED,
    NO_SSH_CONFIG,
    SSH_KEY_MISSING,
    NO_PUBLIC_ROUTE,
    XRAY_CORE_MISSING,
    NO_SELECTED_APPS,
    VPN_PERMISSION_REQUIRED,
}

internal sealed interface QuickTileConnectPlan {
    data class Connect(val owner: VpnSessionOwner) : QuickTileConnectPlan

    data class OpenApp(val tab: GlobalTab, val reason: QuickTileOpenAppReason) : QuickTileConnectPlan
}

/** Without a recorded mode (fresh install, upgrade) the tile keeps its original SSH behaviour. */
internal fun quickTileConnectTarget(lastOwner: VpnSessionOwner?): VpnSessionOwner {
    return lastOwner ?: VpnSessionOwner.SHADOW_SSH
}

/**
 * Never falls back to another mode: the tile restores the mode the user last started, so whatever
 * that mode is missing is shown on its own tab instead. Consent is checked first because the tab's
 * consent dialog must come before any other complaint about it.
 */
internal fun resolveQuickTileConnectPlan(preconditions: QuickTilePreconditions): QuickTileConnectPlan {
    val owner: VpnSessionOwner
    val modeReason: QuickTileOpenAppReason?
    when (preconditions) {
        is QuickTilePreconditions.ShadowSsh -> {
            owner = VpnSessionOwner.SHADOW_SSH
            modeReason = when {
                !preconditions.configSelected -> QuickTileOpenAppReason.NO_SSH_CONFIG
                !preconditions.privateKeyReady -> QuickTileOpenAppReason.SSH_KEY_MISSING
                else -> null
            }
        }
        is QuickTilePreconditions.OpenSource -> {
            owner = VpnSessionOwner.OPEN_SOURCE
            modeReason = when {
                !preconditions.consentAccepted -> QuickTileOpenAppReason.CONSENT_REQUIRED
                !preconditions.routeSelected -> QuickTileOpenAppReason.NO_PUBLIC_ROUTE
                !preconditions.xrayCoreAvailable -> QuickTileOpenAppReason.XRAY_CORE_MISSING
                else -> null
            }
        }
        is QuickTilePreconditions.SmartConnect -> {
            owner = VpnSessionOwner.SMART_CONNECT
            modeReason = when {
                !preconditions.consentAccepted -> QuickTileOpenAppReason.CONSENT_REQUIRED
                !preconditions.xrayCoreAvailable -> QuickTileOpenAppReason.XRAY_CORE_MISSING
                else -> null
            }
        }
    }
    val reason = modeReason ?: when {
        !preconditions.selectedAppsReady -> QuickTileOpenAppReason.NO_SELECTED_APPS
        !preconditions.vpnPermissionGranted -> QuickTileOpenAppReason.VPN_PERMISSION_REQUIRED
        else -> null
    }
    return if (reason == null) {
        QuickTileConnectPlan.Connect(owner)
    } else {
        QuickTileConnectPlan.OpenApp(owner.globalTab(), reason)
    }
}

/**
 * A failed precondition must not overwrite a start that raced in from the app meanwhile. A Routes or
 * Auto use case that threw after its commit point, though, left CONNECTING for [target] with no
 * service behind it; that state belongs to this tap and has to become the error, or the tile stays
 * "Connecting". ConnectVpnUseCase releases such a commit itself, so a Server CONNECTING is never this
 * tap's: its use case threw before it committed (reading the server or its key), and the session was
 * started meanwhile by the app or Always-on VPN. An error would take the state from under that live
 * SshVpnService.
 */
internal fun canPublishQuickTileStartFailure(
    state: VpnConnectionState,
    target: VpnSessionOwner,
    connectAttempted: Boolean,
): Boolean {
    if (canPublishVpnStartFailure(state)) return true
    return connectAttempted &&
        target != VpnSessionOwner.SHADOW_SSH &&
        state.sessionOwner == target &&
        state.status == VpnConnectionStatus.CONNECTING
}

/**
 * Why a Server start of the tile or Always-on VPN started nothing when the connection state does not
 * say so: the session that owned the VPN did not let go in time, or a precondition failed while another
 * session owned the state (ConnectVpnUseCase leaves a live owner's state alone). Null when the state
 * tells: the start went ahead, a starting or running Server session was left alone, or the use case
 * published its error.
 */
internal fun unpublishedServerStartFailure(result: ConnectVpnResult): String? = when (result) {
    ConnectVpnResult.PreviousSessionActive -> SERVER_PREVIOUS_SESSION_ACTIVE
    is ConnectVpnResult.Rejected -> result.message.takeUnless { result.published }
    ConnectVpnResult.Started,
    ConnectVpnResult.Skipped,
    -> null
}

/** The tile's start of [owner] failed with [message]; the app opens on its tab, the log keeps why. */
internal fun quickTileStartFailureDiagnostic(owner: VpnSessionOwner, message: String): String {
    return "Quick settings tile: ${owner.diagnosticModeLabel()} failed to start: $message"
}

internal const val SERVER_PREVIOUS_SESSION_ACTIVE = "The previous connection is still stopping"

/**
 * The tile's icon (System.dc.html): the shield with a check while connected, with an alert for "Check
 * app", and the plain shield otherwise (Off, Connecting, Reconnecting, Disconnecting).
 */
@DrawableRes
internal fun quickTileIconRes(status: VpnConnectionStatus): Int = when (status) {
    VpnConnectionStatus.CONNECTED -> R.drawable.ic_shadow_shield_check
    VpnConnectionStatus.ERROR -> R.drawable.ic_shadow_shield_alert
    VpnConnectionStatus.DISCONNECTED,
    VpnConnectionStatus.CONNECTING,
    VpnConnectionStatus.RECONNECTING,
    VpnConnectionStatus.DISCONNECTING,
    -> R.drawable.ic_shadow_shield
}

/** The mode the tile names: the live session's owner, otherwise the mode a click would start. */
internal fun quickTileSubtitleMode(
    state: VpnConnectionState,
    lastOwner: VpnSessionOwner?,
): VpnSessionOwner {
    val liveOwner = state.sessionOwner.takeIf { state.status in QUICK_TILE_LIVE_STATUSES }
    return liveOwner ?: quickTileConnectTarget(lastOwner)
}

internal fun VpnSessionOwner.globalTab(): GlobalTab {
    return when (this) {
        VpnSessionOwner.SHADOW_SSH -> GlobalTab.SHADOW_SSH
        VpnSessionOwner.OPEN_SOURCE -> GlobalTab.OPEN_SOURCE
        VpnSessionOwner.SMART_CONNECT -> GlobalTab.SMART_CONNECT
    }
}

private val QUICK_TILE_LIVE_STATUSES = setOf(
    VpnConnectionStatus.CONNECTING,
    VpnConnectionStatus.CONNECTED,
    VpnConnectionStatus.RECONNECTING,
    VpnConnectionStatus.DISCONNECTING,
)
