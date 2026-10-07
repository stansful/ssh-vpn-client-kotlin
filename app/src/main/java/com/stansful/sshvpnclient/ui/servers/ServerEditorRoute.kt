package com.stansful.sshvpnclient.ui.servers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.stansful.sshvpnclient.ui.configedit.EditConfigViewModel
import com.stansful.sshvpnclient.ui.designsystem.LocalToaster
import com.stansful.sshvpnclient.ui.keys.SshKeyTraitsReader
import com.stansful.sshvpnclient.ui.shell.Destinations
import com.stansful.sshvpnclient.ui.shell.LocalAppContainer
import com.stansful.sshvpnclient.ui.shell.navigateBack
import com.stansful.sshvpnclient.ui.shell.navigateTo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Server editor; [configId] is null for a new server. */
@Composable
fun ServerEditorRoute(
    navController: NavHostController,
    configId: String?,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel {
        EditConfigViewModel(
            configId = configId,
            addSshConfigUseCase = container.addSshConfigUseCase,
            updateSshConfigUseCase = container.updateSshConfigUseCase,
            getSshConfigByIdUseCase = container.getSshConfigByIdUseCase,
            getSshPrivateKeyListUseCase = container.getSshPrivateKeyListUseCase,
            keyTraitsReader = SshKeyTraitsReader(container.getSshPrivateKeyByIdUseCase),
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(state.isSaved) {
        if (!state.isSaved) return@LaunchedEffect
        // Let the mint "Saved" button register, then close like before.
        delay(SAVED_HOLD_MS)
        val name = state.form.name.trim().ifEmpty { "Server" }
        // Sent to the background during the hold: close (navigateBack needs this entry resumed) and
        // toast once the editor is back on screen.
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        toaster.show("$name is now first in Servers")
        navController.navigateBack()
    }

    ServerEditorScreen(
        state = state,
        onBack = { navController.navigateBack() },
        onSave = viewModel::save,
        onFormChange = viewModel::updateForm,
        onAuthTypeChange = viewModel::selectAuthType,
        onKeySelect = viewModel::selectPrivateKey,
        onAddKey = { navController.navigateTo(Destinations.keyEdit()) },
        onOpenActivity = { navController.navigateTo(Destinations.ACTIVITY) },
    )
}

private const val SAVED_HOLD_MS = 600L
