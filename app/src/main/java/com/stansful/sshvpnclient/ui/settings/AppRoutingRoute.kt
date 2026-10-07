package com.stansful.sshvpnclient.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.ui.apppicker.AppPickerViewModel
import com.stansful.sshvpnclient.ui.apppicker.AppRoutingEvent
import com.stansful.sshvpnclient.ui.designsystem.LocalToaster
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ToastTone
import com.stansful.sshvpnclient.ui.shell.LocalAppContainer
import com.stansful.sshvpnclient.ui.shell.navigateBack
import com.stansful.sshvpnclient.ui.shell.screenViewModel
import kotlinx.coroutines.delay

/**
 * App routing: all apps or only selected apps. Changes apply right away; when the VPN is up the owning
 * view model reconnects it, and this screen narrates that with toasts.
 */
@Composable
fun AppRoutingRoute(
    navController: NavHostController,
) {
    val viewModel: AppPickerViewModel = screenViewModel()
    val connectionRepository = LocalAppContainer.current.vpnConnectionRepository
    val picker by viewModel.uiState.collectAsStateWithLifecycle()
    val vpn by connectionRepository.state.collectAsStateWithLifecycle(connectionRepository.currentState)
    val toaster = LocalToaster.current
    var awaitingReconnect by remember { mutableStateOf(false) }
    var reconnecting by remember { mutableStateOf(false) }
    var turnedOnAtMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(viewModel) {
        viewModel.startSession()
        viewModel.events.collect { event ->
            when (event) {
                AppRoutingEvent.SelectedAppsOn -> {
                    turnedOnAtMs = System.currentTimeMillis()
                    toaster.show(
                        message = "Only selected apps is on now",
                        tone = ToastTone.Success,
                        icon = ShadowIcons.CheckCircle,
                    )
                }
                AppRoutingEvent.RoutingChanged -> if (vpn.status in ActiveStatuses) {
                    awaitingReconnect = true
                    reconnecting = false
                }
            }
        }
    }

    // Follows the reconnect the change caused: "Reconnecting…" while it is down, then the result.
    LaunchedEffect(awaitingReconnect, vpn.status) {
        if (!awaitingReconnect) return@LaunchedEffect
        when (vpn.status) {
            VpnConnectionStatus.CONNECTED -> if (reconnecting) {
                val apps = picker.usableCount
                toaster.show(
                    message = if (picker.vpnMode == VpnMode.SELECTED_APPS) {
                        "Reconnected · ${appsCount(apps)} ${if (apps == 1) "uses" else "use"} the VPN"
                    } else {
                        "Reconnected · all apps use the VPN"
                    },
                    tone = ToastTone.Success,
                    icon = ShadowIcons.CheckCircle,
                )
                awaitingReconnect = false
                reconnecting = false
            } else {
                delay(RECONNECT_START_TIMEOUT_MS)
                awaitingReconnect = false
            }
            VpnConnectionStatus.ERROR -> {
                awaitingReconnect = false
                reconnecting = false
            }
            else -> {
                if (!reconnecting) {
                    reconnecting = true
                    val sinceTurnedOn = System.currentTimeMillis() - turnedOnAtMs
                    if (sinceTurnedOn in 0 until TURNED_ON_TOAST_MS) delay(TURNED_ON_TOAST_MS - sinceTurnedOn)
                    toaster.show(
                        message = "Reconnecting to apply your changes…",
                        tone = ToastTone.Neutral,
                        icon = ShadowIcons.Refresh,
                        durationMillis = RECONNECT_TIMEOUT_MS,
                    )
                }
                delay(RECONNECT_TIMEOUT_MS)
                awaitingReconnect = false
                reconnecting = false
            }
        }
    }

    val leave = {
        viewModel.saveSelection()
        navController.navigateBack()
        Unit
    }
    BackHandler(onBack = leave)

    AppRoutingScreen(
        state = AppRoutingUiState(
            picker = picker,
            connection = when {
                awaitingReconnect && reconnecting -> RoutingConnection.Reconnecting
                vpn.status == VpnConnectionStatus.CONNECTED -> RoutingConnection.Connected
                vpn.status == VpnConnectionStatus.RECONNECTING -> RoutingConnection.Reconnecting
                vpn.status == VpnConnectionStatus.CONNECTING -> RoutingConnection.Connecting
                else -> RoutingConnection.NotConnected
            },
        ),
        actions = AppRoutingActions(
            onBack = leave,
            onChooseAllApps = viewModel::chooseAllApps,
            onChooseSelectedApps = viewModel::chooseSelectedApps,
            onEditList = viewModel::editListWhileAllApps,
            onFinishEditing = viewModel::finishEditingList,
            onQueryChange = viewModel::setQuery,
            onShowSystemAppsChange = viewModel::setShowSystemApps,
            onTogglePackage = viewModel::togglePackage,
            onRetry = viewModel::retryLoading,
        ),
    )
}

private val ActiveStatuses = setOf(
    VpnConnectionStatus.CONNECTING,
    VpnConnectionStatus.CONNECTED,
    VpnConnectionStatus.RECONNECTING,
)

/** How long a change may take to start reconnecting before the screen stops waiting for it. */
private const val RECONNECT_START_TIMEOUT_MS = 3_000L
private const val RECONNECT_TIMEOUT_MS = 15_000L
private const val TURNED_ON_TOAST_MS = 1_500L
