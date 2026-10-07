package com.stansful.sshvpnclient.ui.servers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.stansful.sshvpnclient.ui.configs.ConfigListEvent
import com.stansful.sshvpnclient.ui.configs.ConfigListViewModel
import com.stansful.sshvpnclient.ui.designsystem.LocalToaster
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ToastTone
import com.stansful.sshvpnclient.ui.keys.KeyListEvent
import com.stansful.sshvpnclient.ui.keys.KeyListViewModel
import com.stansful.sshvpnclient.ui.keys.SshKeyTraitsReader
import com.stansful.sshvpnclient.ui.main.MainViewModel
import com.stansful.sshvpnclient.ui.shell.Destinations
import com.stansful.sshvpnclient.ui.shell.LocalAppContainer
import com.stansful.sshvpnclient.ui.shell.activityViewModel
import com.stansful.sshvpnclient.ui.shell.navigateTo
import com.stansful.sshvpnclient.ui.shell.screenViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Servers and keys (segmented `Servers · Keys`); [initialTab] is `servers` or `keys`. */
@Composable
fun ServersRoute(
    navController: NavHostController,
    initialTab: String?,
) {
    val container = LocalAppContainer.current
    val serversViewModel = screenViewModel<ConfigListViewModel>()
    val keysViewModel = viewModel {
        KeyListViewModel(
            getSshPrivateKeyListUseCase = container.getSshPrivateKeyListUseCase,
            deleteSshPrivateKeyUseCase = container.deleteSshPrivateKeyUseCase,
            keyTraitsReader = SshKeyTraitsReader(container.getSshPrivateKeyByIdUseCase),
        )
    }
    val mainViewModel = activityViewModel<MainViewModel>()
    val servers by serversViewModel.uiState.collectAsStateWithLifecycle()
    val keys by keysViewModel.uiState.collectAsStateWithLifecycle()
    // Only the Server-mode session matters here; the rest of the main state (activity log lines,
    // terminal output) changes often and must not recompose the lists.
    val connectionFlow = remember(mainViewModel) {
        mainViewModel.uiState.map { it.serverConnection() }.distinctUntilChanged()
    }
    val connection by connectionFlow.collectAsStateWithLifecycle(
        // Seeded with the current session, so the lists don't flash "not connected" for a frame.
        remember(mainViewModel) { mainViewModel.currentServerConnection() },
    )
    val toaster = LocalToaster.current

    var tab by rememberSaveable { mutableStateOf(ServersTab.fromArg(initialTab) ?: ServersTab.Servers) }
    // A `tab` argument re-delivered to the open destination switches the tab; the same argument seen
    // again after a rotation or a trip to an editor keeps the tab the user picked.
    var handledTabArg by rememberSaveable { mutableStateOf(initialTab) }
    LaunchedEffect(initialTab) {
        if (initialTab == handledTabArg) return@LaunchedEffect
        handledTabArg = initialTab
        ServersTab.fromArg(initialTab)?.let { tab = it }
    }

    var shake by remember { mutableStateOf<ShakeRequest?>(null) }
    val latestServers by rememberUpdatedState(servers)
    val latestConnection by rememberUpdatedState(connection)
    // "Reconnecting through X…" stays up until the session runs through X (or times out).
    var reconnectToast by remember { mutableStateOf<ReconnectToast?>(null) }
    LaunchedEffect(connection, reconnectToast) {
        val pending = reconnectToast ?: return@LaunchedEffect
        val session = connection
        if (session?.phase == ConnectionPhase.Connected && session.configId == pending.configId) {
            toaster.dismiss(pending.toastId)
            reconnectToast = null
        }
    }
    LaunchedEffect(serversViewModel) {
        serversViewModel.events.collect { event ->
            when (event) {
                is ConfigListEvent.Deleted -> toaster.show("Server deleted")
                is ConfigListEvent.Selected -> {
                    val name = latestServers.items.firstOrNull { it.config.id == event.id }?.config?.name
                        ?: "This server"
                    val session = latestConnection
                    if (session != null && session.configId != event.id) {
                        // Like Home's "Choose server" (Servers.dc.html): switching while connected
                        // reconnects through the new server.
                        mainViewModel.reconnectThroughSelectedServer()
                        toaster.show(
                            message = "Reconnecting through $name…",
                            tone = ToastTone.Neutral,
                            icon = ShadowIcons.Refresh,
                            durationMillis = RECONNECT_TOAST_MS,
                        )
                        reconnectToast = toaster.current?.let { ReconnectToast(event.id, it.id) }
                    } else {
                        toaster.show("$name will be used next time you connect")
                    }
                }
                is ConfigListEvent.SelectFailed -> {
                    shake = ShakeRequest(event.id, (shake?.nonce ?: 0) + 1)
                    toaster.show("Couldn’t save your choice. Try again.", tone = ToastTone.Error)
                }
            }
        }
    }
    LaunchedEffect(keysViewModel) {
        keysViewModel.events.collect { event ->
            when (event) {
                is KeyListEvent.Deleted -> toaster.show("Key deleted")
            }
        }
    }
    val freshKeyId = rememberFreshKeyId()

    val actions = rememberServersActions(
        navController = navController,
        serversViewModel = serversViewModel,
        keysViewModel = keysViewModel,
        mainViewModel = mainViewModel,
        onTabChange = { tab = it },
    )

    ServersScreen(
        state = ServersScreenState(
            tab = tab,
            servers = servers,
            keys = keys,
            connection = connection,
            shake = shake,
            freshKeyId = freshKeyId,
        ),
        actions = actions,
    )
}

/** The screen's callbacks, created once per view models, so the lists do not recompose for new lambdas. */
@Composable
private fun rememberServersActions(
    navController: NavHostController,
    serversViewModel: ConfigListViewModel,
    keysViewModel: KeyListViewModel,
    mainViewModel: MainViewModel,
    onTabChange: (ServersTab) -> Unit,
): ServersActions {
    val toaster = LocalToaster.current
    val currentOnTabChange by rememberUpdatedState(onTabChange)
    return remember(navController, serversViewModel, keysViewModel, mainViewModel, toaster) {
        ServersActions(
            onTabChange = { currentOnTabChange(it) },
            onAddServer = { navController.navigateTo(Destinations.serverEdit()) },
            onAddKey = { navController.navigateTo(Destinations.keyEdit()) },
            // The toast (and the reconnect while connected) follows the stored selection: ConfigListEvent.Selected.
            onSelectServer = { item -> if (!item.isSelected) serversViewModel.select(item.config.id) },
            onEditServer = { id -> navController.navigateTo(Destinations.serverEdit(id)) },
            onAskDeleteServer = serversViewModel::askDelete,
            onConfirmDeleteServer = serversViewModel::confirmDelete,
            onCancelDeleteServer = serversViewModel::cancelDelete,
            onDisconnect = {
                mainViewModel.disconnect()
                toaster.show("Disconnected", tone = ToastTone.Info)
            },
            onEditKey = { id -> navController.navigateTo(Destinations.keyEdit(id)) },
            onAskDeleteKey = keysViewModel::askDelete,
            onConfirmDeleteKey = keysViewModel::confirmDelete,
            onCancelDeleteKey = keysViewModel::cancelDelete,
            onOpenServer = { id ->
                keysViewModel.cancelDelete()
                navController.navigateTo(Destinations.serverEdit(id))
            },
        )
    }
}

/**
 * Id of the key the key editor just saved (handed back through this entry's savedStateHandle), held
 * long enough for its card to flash, then cleared.
 */
@Composable
private fun rememberFreshKeyId(): String? {
    val savedStateHandle = (LocalViewModelStoreOwner.current as? NavBackStackEntry)?.savedStateHandle
    val freshKeyFlow = remember(savedStateHandle) {
        savedStateHandle?.getStateFlow<String?>(FRESH_KEY_ID, null) ?: MutableStateFlow(null)
    }
    val freshKeyId by freshKeyFlow.collectAsStateWithLifecycle()
    LaunchedEffect(freshKeyId) {
        if (freshKeyId == null) return@LaunchedEffect
        delay(FRESH_KEY_HOLD_MS)
        savedStateHandle?.set(FRESH_KEY_ID, null)
    }
    return freshKeyId
}

/** The "Reconnecting through X…" toast ([toastId]) shown until the session runs through [configId]. */
private data class ReconnectToast(val configId: String, val toastId: Long)

private const val RECONNECT_TOAST_MS = 15_000L

/** savedStateHandle key through which [KeyEditorRoute] hands the id of the key it saved back. */
internal const val FRESH_KEY_ID = "servers.freshKeyId"
private const val FRESH_KEY_HOLD_MS = 1_800L

/** The Server-mode session right now (outside composition: a one-off read to seed the observed state). */
private fun MainViewModel.currentServerConnection(): ServerConnection? = uiState.value.serverConnection()
