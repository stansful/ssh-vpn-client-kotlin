package com.stansful.sshvpnclient.vpn

import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner

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
 * A failed precondition must not overwrite a start that raced in from the app meanwhile. A use case
 * that threw after its commit point, though, left CONNECTING for [target] with no service behind
 * it; that state belongs to this tap and has to become the error, or the tile stays "Connecting".
 */
internal fun canPublishQuickTileStartFailure(
    state: VpnConnectionState,
    target: VpnSessionOwner,
    connectAttempted: Boolean,
): Boolean {
    if (canPublishVpnStartFailure(state)) return true
    return connectAttempted &&
        state.sessionOwner == target &&
        state.status == VpnConnectionStatus.CONNECTING
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
