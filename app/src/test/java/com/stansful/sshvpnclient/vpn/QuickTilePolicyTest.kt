package com.stansful.sshvpnclient.vpn

import com.stansful.sshvpnclient.R
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectVpnResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickTilePolicyTest {
    @Test
    fun `tile icon is the shield with a check when connected and an alert for Check app`() {
        assertEquals(R.drawable.ic_shadow_shield_check, quickTileIconRes(VpnConnectionStatus.CONNECTED))
        assertEquals(R.drawable.ic_shadow_shield_alert, quickTileIconRes(VpnConnectionStatus.ERROR))
        listOf(
            VpnConnectionStatus.DISCONNECTED,
            VpnConnectionStatus.CONNECTING,
            VpnConnectionStatus.RECONNECTING,
            VpnConnectionStatus.DISCONNECTING,
        ).forEach { status -> assertEquals(status.name, R.drawable.ic_shadow_shield, quickTileIconRes(status)) }
    }

    @Test
    fun `tile without a recorded mode keeps starting ssh`() {
        assertEquals(VpnSessionOwner.SHADOW_SSH, quickTileConnectTarget(null))
    }

    @Test
    fun `tile targets the last recorded mode`() {
        VpnSessionOwner.entries.forEach { owner ->
            assertEquals(owner, quickTileConnectTarget(owner))
        }
    }

    @Test
    fun `satisfied preconditions connect exactly the target mode`() {
        assertEquals(
            QuickTileConnectPlan.Connect(VpnSessionOwner.SHADOW_SSH),
            resolveQuickTileConnectPlan(readySsh()),
        )
        assertEquals(
            QuickTileConnectPlan.Connect(VpnSessionOwner.OPEN_SOURCE),
            resolveQuickTileConnectPlan(readyOpenSource()),
        )
        assertEquals(
            QuickTileConnectPlan.Connect(VpnSessionOwner.SMART_CONNECT),
            resolveQuickTileConnectPlan(readySmart()),
        )
    }

    @Test
    fun `ssh failures open the ssh tab with the failing reason`() {
        assertOpensApp(
            GlobalTab.SHADOW_SSH,
            QuickTileOpenAppReason.NO_SSH_CONFIG,
            readySsh().copy(configSelected = false, privateKeyReady = false),
        )
        assertOpensApp(
            GlobalTab.SHADOW_SSH,
            QuickTileOpenAppReason.SSH_KEY_MISSING,
            readySsh().copy(privateKeyReady = false),
        )
        assertOpensApp(
            GlobalTab.SHADOW_SSH,
            QuickTileOpenAppReason.NO_SELECTED_APPS,
            readySsh().copy(selectedAppsReady = false),
        )
        assertOpensApp(
            GlobalTab.SHADOW_SSH,
            QuickTileOpenAppReason.VPN_PERMISSION_REQUIRED,
            readySsh().copy(vpnPermissionGranted = false),
        )
    }

    @Test
    fun `open source failures open the public tab with the failing reason`() {
        assertOpensApp(
            GlobalTab.OPEN_SOURCE,
            QuickTileOpenAppReason.NO_PUBLIC_ROUTE,
            readyOpenSource().copy(routeSelected = false),
        )
        assertOpensApp(
            GlobalTab.OPEN_SOURCE,
            QuickTileOpenAppReason.XRAY_CORE_MISSING,
            readyOpenSource().copy(xrayCoreAvailable = false),
        )
        assertOpensApp(
            GlobalTab.OPEN_SOURCE,
            QuickTileOpenAppReason.NO_SELECTED_APPS,
            readyOpenSource().copy(selectedAppsReady = false),
        )
        assertOpensApp(
            GlobalTab.OPEN_SOURCE,
            QuickTileOpenAppReason.VPN_PERMISSION_REQUIRED,
            readyOpenSource().copy(vpnPermissionGranted = false),
        )
    }

    @Test
    fun `smart failures open the smart tab with the failing reason`() {
        assertOpensApp(
            GlobalTab.SMART_CONNECT,
            QuickTileOpenAppReason.XRAY_CORE_MISSING,
            readySmart().copy(xrayCoreAvailable = false),
        )
        assertOpensApp(
            GlobalTab.SMART_CONNECT,
            QuickTileOpenAppReason.NO_SELECTED_APPS,
            readySmart().copy(selectedAppsReady = false),
        )
        assertOpensApp(
            GlobalTab.SMART_CONNECT,
            QuickTileOpenAppReason.VPN_PERMISSION_REQUIRED,
            readySmart().copy(vpnPermissionGranted = false),
        )
    }

    @Test
    fun `missing consent is reported before any other failure of the tab`() {
        assertOpensApp(
            GlobalTab.OPEN_SOURCE,
            QuickTileOpenAppReason.CONSENT_REQUIRED,
            QuickTilePreconditions.OpenSource(
                consentAccepted = false,
                routeSelected = false,
                xrayCoreAvailable = false,
                selectedAppsReady = false,
                vpnPermissionGranted = false,
            ),
        )
        assertOpensApp(
            GlobalTab.SMART_CONNECT,
            QuickTileOpenAppReason.CONSENT_REQUIRED,
            QuickTilePreconditions.SmartConnect(
                consentAccepted = false,
                xrayCoreAvailable = false,
                selectedAppsReady = false,
                vpnPermissionGranted = false,
            ),
        )
    }

    @Test
    fun `mode specific failures are reported before selected apps and permission`() {
        assertOpensApp(
            GlobalTab.SHADOW_SSH,
            QuickTileOpenAppReason.SSH_KEY_MISSING,
            readySsh().copy(
                privateKeyReady = false,
                selectedAppsReady = false,
                vpnPermissionGranted = false,
            ),
        )
        assertOpensApp(
            GlobalTab.OPEN_SOURCE,
            QuickTileOpenAppReason.NO_PUBLIC_ROUTE,
            readyOpenSource().copy(
                routeSelected = false,
                xrayCoreAvailable = false,
                selectedAppsReady = false,
            ),
        )
        assertOpensApp(
            GlobalTab.SMART_CONNECT,
            QuickTileOpenAppReason.NO_SELECTED_APPS,
            readySmart().copy(selectedAppsReady = false, vpnPermissionGranted = false),
        )
    }

    @Test
    fun `failed precondition never overwrites a session that started meanwhile`() {
        assertTrue(
            canPublishQuickTileStartFailure(
                VpnConnectionState(status = VpnConnectionStatus.ERROR, errorMessage = "Previous"),
                VpnSessionOwner.OPEN_SOURCE,
                connectAttempted = false,
            ),
        )
        assertFalse(
            canPublishQuickTileStartFailure(
                connectingState(VpnSessionOwner.OPEN_SOURCE),
                VpnSessionOwner.OPEN_SOURCE,
                connectAttempted = false,
            ),
        )
    }

    @Test
    fun `connect that threw after its commit turns its own connecting state into the error`() {
        listOf(VpnSessionOwner.OPEN_SOURCE, VpnSessionOwner.SMART_CONNECT).forEach { owner ->
            assertTrue(
                canPublishQuickTileStartFailure(
                    connectingState(owner),
                    owner,
                    connectAttempted = true,
                ),
            )
        }
        assertFalse(
            canPublishQuickTileStartFailure(
                connectingState(VpnSessionOwner.SHADOW_SSH),
                VpnSessionOwner.SMART_CONNECT,
                connectAttempted = true,
            ),
        )
        assertFalse(
            canPublishQuickTileStartFailure(
                connectingState(VpnSessionOwner.SMART_CONNECT).copy(status = VpnConnectionStatus.CONNECTED),
                VpnSessionOwner.SMART_CONNECT,
                connectAttempted = true,
            ),
        )
    }

    @Test
    fun `a server start that threw never takes the state of a live server session`() {
        // ConnectVpnUseCase releases its own commit when the service cannot start, so a Server session
        // that is connecting is one the app or Always-on started while the use case read the server.
        assertFalse(
            canPublishQuickTileStartFailure(
                connectingState(VpnSessionOwner.SHADOW_SSH),
                VpnSessionOwner.SHADOW_SSH,
                connectAttempted = true,
            ),
        )
        // The use case's own released commit, or nobody's state: the failure is published.
        assertTrue(
            canPublishQuickTileStartFailure(
                VpnConnectionState(status = VpnConnectionStatus.ERROR, errorMessage = "not allowed"),
                VpnSessionOwner.SHADOW_SSH,
                connectAttempted = true,
            ),
        )
        assertTrue(
            canPublishQuickTileStartFailure(VpnConnectionState(), VpnSessionOwner.SHADOW_SSH, connectAttempted = true),
        )
    }

    @Test
    fun `a server start the state does not explain is named`() {
        assertEquals(
            SERVER_PREVIOUS_SESSION_ACTIVE,
            unpublishedServerStartFailure(ConnectVpnResult.PreviousSessionActive),
        )
        assertEquals(
            "Selected SSH key not found",
            unpublishedServerStartFailure(
                ConnectVpnResult.Rejected("Selected SSH key not found", published = false),
            ),
        )
    }

    @Test
    fun `a server start the state explains needs no message`() {
        assertNull(unpublishedServerStartFailure(ConnectVpnResult.Started))
        // A Server session was already starting or running: the tile leaves it alone.
        assertNull(unpublishedServerStartFailure(ConnectVpnResult.Skipped))
        // The error is the connection state already.
        assertNull(
            unpublishedServerStartFailure(ConnectVpnResult.Rejected("No apps selected", published = true)),
        )
    }

    @Test
    fun `the tile's failed start leaves a log line naming the mode`() {
        assertEquals(
            "Quick settings tile: Server failed to start: The previous connection is still stopping",
            quickTileStartFailureDiagnostic(VpnSessionOwner.SHADOW_SSH, SERVER_PREVIOUS_SESSION_ACTIVE),
        )
        assertEquals(
            "Quick settings tile: Routes failed to start: boom",
            quickTileStartFailureDiagnostic(VpnSessionOwner.OPEN_SOURCE, "boom"),
        )
    }

    @Test
    fun `subtitle names the owner of a live session`() {
        listOf(
            VpnConnectionStatus.CONNECTING,
            VpnConnectionStatus.CONNECTED,
            VpnConnectionStatus.RECONNECTING,
            VpnConnectionStatus.DISCONNECTING,
        ).forEach { status ->
            val state = VpnConnectionState(
                status = status,
                activeConfigId = "public-profile",
                activeTransport = VpnTransportType.XRAY,
                sessionOwner = VpnSessionOwner.OPEN_SOURCE,
            )

            assertEquals(
                VpnSessionOwner.OPEN_SOURCE,
                quickTileSubtitleMode(state, lastOwner = VpnSessionOwner.SMART_CONNECT),
            )
        }
    }

    @Test
    fun `subtitle names the mode a click would start when no session is live`() {
        assertEquals(
            VpnSessionOwner.SMART_CONNECT,
            quickTileSubtitleMode(VpnConnectionState(), lastOwner = VpnSessionOwner.SMART_CONNECT),
        )
        assertEquals(
            VpnSessionOwner.OPEN_SOURCE,
            quickTileSubtitleMode(
                VpnConnectionState(
                    status = VpnConnectionStatus.ERROR,
                    activeConfigId = "public-profile",
                    errorMessage = "No apps selected",
                ),
                lastOwner = VpnSessionOwner.OPEN_SOURCE,
            ),
        )
        assertEquals(
            VpnSessionOwner.SHADOW_SSH,
            quickTileSubtitleMode(VpnConnectionState(), lastOwner = null),
        )
    }

    @Test
    fun `subtitle falls back to the last mode when a live state has no owner`() {
        val state = VpnConnectionState(status = VpnConnectionStatus.DISCONNECTING)

        assertEquals(
            VpnSessionOwner.SMART_CONNECT,
            quickTileSubtitleMode(state, lastOwner = VpnSessionOwner.SMART_CONNECT),
        )
    }

    private fun assertOpensApp(
        tab: GlobalTab,
        reason: QuickTileOpenAppReason,
        preconditions: QuickTilePreconditions,
    ) {
        assertEquals(
            QuickTileConnectPlan.OpenApp(tab, reason),
            resolveQuickTileConnectPlan(preconditions),
        )
    }

    private fun connectingState(owner: VpnSessionOwner) = VpnConnectionState(
        status = VpnConnectionStatus.CONNECTING,
        activeTransport = if (owner == VpnSessionOwner.SHADOW_SSH) {
            VpnTransportType.SSH
        } else {
            VpnTransportType.XRAY
        },
        sessionOwner = owner,
    )

    private fun readySsh() = QuickTilePreconditions.ShadowSsh(
        configSelected = true,
        privateKeyReady = true,
        selectedAppsReady = true,
        vpnPermissionGranted = true,
    )

    private fun readyOpenSource() = QuickTilePreconditions.OpenSource(
        consentAccepted = true,
        routeSelected = true,
        xrayCoreAvailable = true,
        selectedAppsReady = true,
        vpnPermissionGranted = true,
    )

    private fun readySmart() = QuickTilePreconditions.SmartConnect(
        consentAccepted = true,
        xrayCoreAvailable = true,
        selectedAppsReady = true,
        vpnPermissionGranted = true,
    )
}
