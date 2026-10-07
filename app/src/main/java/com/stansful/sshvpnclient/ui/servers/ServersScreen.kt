package com.stansful.sshvpnclient.ui.servers

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.configs.ConfigListItem
import com.stansful.sshvpnclient.ui.configs.ConfigListUiState
import com.stansful.sshvpnclient.ui.designsystem.SegmentOption
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButtonStyle
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowSegmented
import com.stansful.sshvpnclient.ui.designsystem.TopLevelBar
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.keys.KeyListUiState
import com.stansful.sshvpnclient.ui.shell.LocalWindowWidthClass
import com.stansful.sshvpnclient.ui.shell.WindowWidthClass
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowDimens
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.shadowTween
import java.time.LocalDate

/** A card to shake once (its selection could not be stored); [nonce] restarts the shake. */
@Immutable
internal data class ShakeRequest(val id: String, val nonce: Int)

/** Everything the Servers destination renders; [freshKeyId] = the key just saved (its card flashes). */
@Immutable
internal data class ServersScreenState(
    val tab: ServersTab = ServersTab.Servers,
    val servers: ConfigListUiState = ConfigListUiState(),
    val keys: KeyListUiState = KeyListUiState(),
    val connection: ServerConnection? = null,
    val shake: ShakeRequest? = null,
    val freshKeyId: String? = null,
)

@Stable
internal class ServersActions(
    val onTabChange: (ServersTab) -> Unit = {},
    val onAddServer: () -> Unit = {},
    val onAddKey: () -> Unit = {},
    val onSelectServer: (ConfigListItem) -> Unit = {},
    val onEditServer: (String) -> Unit = {},
    val onAskDeleteServer: (String) -> Unit = {},
    val onConfirmDeleteServer: () -> Unit = {},
    val onCancelDeleteServer: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onEditKey: (String) -> Unit = {},
    val onAskDeleteKey: (String) -> Unit = {},
    val onConfirmDeleteKey: () -> Unit = {},
    val onCancelDeleteKey: () -> Unit = {},
    val onOpenServer: (String) -> Unit = {},
)

/**
 * Top-level "Servers" destination: the segmented `Servers · Keys` switch over the server list (pick the
 * server Server mode uses) and the key list.
 */
@Composable
internal fun ServersScreen(
    state: ServersScreenState,
    actions: ServersActions,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    val serversListState = rememberLazyListState()
    val keysListState = rememberLazyListState()
    val keysTab = state.tab == ServersTab.Keys
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Shadow.colors.bg),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = MAX_LIST_WIDTH)
                .fillMaxWidth(),
        ) {
            TopLevelBar(
                title = "Servers",
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp),
                pane = LocalWindowWidthClass.current == WindowWidthClass.Expanded,
                modifier = Modifier.fadeUpIn(0),
            ) {
                ShadowIconButton(
                    icon = ShadowIcons.Plus,
                    contentDescription = if (keysTab) "Add key" else "Add server",
                    onClick = if (keysTab) actions.onAddKey else actions.onAddServer,
                    style = ShadowIconButtonStyle.Tonal,
                )
            }
            ShadowSegmented(
                options = listOf(
                    SegmentOption(
                        label = "Servers",
                        icon = ShadowIcons.Server,
                        count = state.servers.items.size.toString().takeIf { state.servers.isLoaded },
                    ),
                    SegmentOption(
                        label = "Keys",
                        icon = ShadowIcons.Key,
                        count = state.keys.items.size.toString().takeIf { state.keys.isLoaded },
                    ),
                ),
                selectedIndex = state.tab.ordinal,
                onSelect = { index -> actions.onTabChange(ServersTab.entries[index]) },
                modifier = Modifier
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp)
                    .fadeUpIn(1),
            )
            val tabFade = shadowTween<Float>(ShadowMotion.Small)
            AnimatedContent(
                targetState = state.tab,
                transitionSpec = { fadeIn(tabFade) togetherWith fadeOut(tabFade) },
                label = "servers-tab",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { tab ->
                when (tab) {
                    ServersTab.Servers -> ServersTabContent(
                        state = state.servers,
                        connection = state.connection,
                        shake = state.shake,
                        actions = actions,
                        listState = serversListState,
                    )
                    ServersTab.Keys -> KeysTabContent(
                        state = state.keys,
                        freshKeyId = state.freshKeyId,
                        today = today,
                        actions = actions,
                        listState = keysListState,
                    )
                }
            }
        }
    }
    ServerDeleteDialogHost(state = state.servers, connection = state.connection, actions = actions)
    KeyDeleteDialogHost(keys = state.keys, servers = state.servers.items, actions = actions)
}

private val MAX_LIST_WIDTH = ShadowDimens.PaneMaxWidth
