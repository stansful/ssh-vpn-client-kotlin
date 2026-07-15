package com.stansful.sshvpnclient.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.R
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.OpenSourcePolicy
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.ui.apppicker.AppPickerRoute
import com.stansful.sshvpnclient.ui.opensource.OpenSourceRoute
import com.stansful.sshvpnclient.ui.smartconnect.SmartConnectRoute
import com.stansful.sshvpnclient.work.ProxySourceSyncWorker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalTabsHost(
    container: AppContainer,
    navController: NavHostController,
) {
    val settings by container.appSettingsRepository.settings.collectAsStateWithLifecycle()
    val visibleTab = settings.activeGlobalTab
    var consentTab by remember { mutableStateOf<GlobalTab?>(null) }
    var showGlobalAppPicker by rememberSaveable { mutableStateOf(false) }
    var activateSelectedAppsAfterPicker by rememberSaveable { mutableStateOf(false) }

    val openGlobalAppPicker = {
        // When SELECTED_APPS has no packages yet, VpnModeSelector deliberately leaves the
        // previous valid mode in place. Commit the new mode only after the picker saves at least
        // one package, so a live VPN never observes SELECTED_APPS + empty set.
        activateSelectedAppsAfterPicker = settings.vpnMode != VpnMode.SELECTED_APPS
        showGlobalAppPicker = true
        Unit
    }

    LaunchedEffect(
        settings.activeGlobalTab,
        settings.showOpenSourceWarningOnEnter,
        settings.showSmartConnectWarningOnEnter,
        settings.smartConnectConsentVersion,
    ) {
        consentTab = when (settings.activeGlobalTab) {
            GlobalTab.OPEN_SOURCE -> GlobalTab.OPEN_SOURCE
                .takeIf { settings.showOpenSourceWarningOnEnter }
            GlobalTab.SMART_CONNECT -> GlobalTab.SMART_CONNECT
                .takeIf {
                    settings.showSmartConnectWarningOnEnter &&
                        settings.smartConnectConsentVersion < OpenSourcePolicy.CONSENT_VERSION
                }
            GlobalTab.SHADOW_SSH -> null
        }
    }

    if (showGlobalAppPicker) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            AppPickerRoute(
                container = container,
                onBack = {
                    showGlobalAppPicker = false
                    val shouldActivateSelectedApps = activateSelectedAppsAfterPicker
                    activateSelectedAppsAfterPicker = false
                    if (shouldActivateSelectedApps &&
                        container.appSettingsRepository.settings.value.selectedAppPackages.isNotEmpty()
                    ) {
                        container.appSettingsRepository.setVpnMode(VpnMode.SELECTED_APPS)
                    }
                },
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        PrimaryTabRow(
            selectedTabIndex = visibleTab.ordinal,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            GlobalTab.entries.forEach { tab ->
                Tab(
                    selected = visibleTab == tab,
                    onClick = {
                        container.appSettingsRepository.setActiveGlobalTab(tab)
                    },
                    text = { Text(tab.label) },
                    icon = {
                        Icon(
                            imageVector = when (tab) {
                                GlobalTab.SHADOW_SSH -> Icons.Default.Terminal
                                GlobalTab.SMART_CONNECT -> Icons.Default.AutoAwesome
                                GlobalTab.OPEN_SOURCE -> Icons.Default.Public
                            },
                            contentDescription = null,
                        )
                    },
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = visibleTab,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "global-tab",
            ) { tab ->
                when (tab) {
                    GlobalTab.SHADOW_SSH -> SshVpnNavGraph(container, navController)
                    GlobalTab.SMART_CONNECT -> SmartConnectRoute(
                        container = container,
                        openAppPicker = openGlobalAppPicker,
                    )
                    GlobalTab.OPEN_SOURCE -> OpenSourceRoute(
                        container = container,
                        openAppPicker = openGlobalAppPicker,
                    )
                }
            }
        }
    }

    consentTab?.let { requestedTab ->
        AlertDialog(
            onDismissRequest = {
                container.appSettingsRepository.setActiveGlobalTab(GlobalTab.SHADOW_SSH)
                consentTab = null
            },
            title = {
                Text(
                    stringResource(
                        if (requestedTab == GlobalTab.SMART_CONNECT) {
                            R.string.smart_connect_warning_title
                        } else {
                            R.string.open_source_warning_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (requestedTab == GlobalTab.SMART_CONNECT) {
                            R.string.smart_connect_warning_message
                        } else {
                            R.string.open_source_warning_message
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (requestedTab == GlobalTab.SMART_CONNECT) {
                            container.appSettingsRepository.setSmartConnectConsentVersion(
                                OpenSourcePolicy.CONSENT_VERSION,
                            )
                        } else {
                            container.appSettingsRepository.setOpenSourceConsentVersion(
                                OpenSourcePolicy.CONSENT_VERSION,
                            )
                            if (container.appSettingsRepository.settings.value.openSourceAutoUpdateEnabled) {
                                ProxySourceSyncWorker.schedule(container.applicationContext)
                            }
                        }
                        container.appSettingsRepository.setActiveGlobalTab(requestedTab)
                        consentTab = null
                    },
                ) { Text(stringResource(R.string.open_source_warning_continue)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        container.appSettingsRepository.setActiveGlobalTab(GlobalTab.SHADOW_SSH)
                        consentTab = null
                    },
                ) { Text(stringResource(R.string.open_source_warning_back)) }
            },
        )
    }
}
