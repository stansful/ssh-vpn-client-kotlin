package com.stansful.sshvpnclient.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.OpenSourcePolicy
import com.stansful.sshvpnclient.ui.opensource.OpenSourceRoute
import com.stansful.sshvpnclient.work.ProxySourceSyncWorker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalTabsHost(
    container: AppContainer,
    navController: NavHostController,
) {
    val settings by container.appSettingsRepository.settings.collectAsStateWithLifecycle()
    val consentAccepted = settings.openSourceConsentVersion >= OpenSourcePolicy.CONSENT_VERSION
    val visibleTab = settings.activeGlobalTab.takeIf { it != GlobalTab.OPEN_SOURCE || consentAccepted }
        ?: GlobalTab.SHADOW_SSH
    var showConsent by remember { mutableStateOf(false) }

    LaunchedEffect(settings.activeGlobalTab, consentAccepted) {
        if (settings.activeGlobalTab == GlobalTab.OPEN_SOURCE && !consentAccepted) {
            showConsent = true
        }
    }

    Column(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = visibleTab.ordinal,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            GlobalTab.entries.forEach { tab ->
                Tab(
                    selected = visibleTab == tab,
                    onClick = {
                        if (tab == GlobalTab.OPEN_SOURCE && !consentAccepted) {
                            showConsent = true
                        } else {
                            container.appSettingsRepository.setActiveGlobalTab(tab)
                        }
                    },
                    text = { Text(tab.label) },
                    icon = {
                        Icon(
                            imageVector = if (tab == GlobalTab.SHADOW_SSH) {
                                Icons.Default.Terminal
                            } else {
                                Icons.Default.Public
                            },
                            contentDescription = null,
                        )
                    },
                )
            }
        }
        Box(modifier = androidx.compose.ui.Modifier.weight(1f)) {
            AnimatedContent(
                targetState = visibleTab,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "global-tab",
            ) { tab ->
                when (tab) {
                    GlobalTab.SHADOW_SSH -> SshVpnNavGraph(container, navController)
                    GlobalTab.OPEN_SOURCE -> OpenSourceRoute(container)
                }
            }
        }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = {
                showConsent = false
                container.appSettingsRepository.setActiveGlobalTab(GlobalTab.SHADOW_SSH)
            },
            title = { Text("Публичные конфигурации") },
            text = { Text(OpenSourcePolicy.DISCLAIMER) },
            confirmButton = {
                TextButton(
                    onClick = {
                        container.appSettingsRepository.setOpenSourceConsentVersion(
                            OpenSourcePolicy.CONSENT_VERSION,
                        )
                        container.appSettingsRepository.setActiveGlobalTab(GlobalTab.OPEN_SOURCE)
                        ProxySourceSyncWorker.schedule(container.applicationContext)
                        showConsent = false
                    },
                ) { Text("Согласен") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        container.appSettingsRepository.setActiveGlobalTab(GlobalTab.SHADOW_SSH)
                        showConsent = false
                    },
                ) { Text("Нет, вернуться назад") }
            },
        )
    }
}
