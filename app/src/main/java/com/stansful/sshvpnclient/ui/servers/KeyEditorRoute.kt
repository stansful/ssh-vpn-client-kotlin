package com.stansful.sshvpnclient.ui.servers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.stansful.sshvpnclient.domain.usecase.config.GetSshConfigListUseCase
import com.stansful.sshvpnclient.ui.designsystem.LocalToaster
import com.stansful.sshvpnclient.ui.keyedit.EditKeyViewModel
import com.stansful.sshvpnclient.ui.shell.LocalAppContainer
import com.stansful.sshvpnclient.ui.shell.navigateBack
import kotlinx.coroutines.flow.first

/** Key editor; [keyId] is null for a new key. */
@Composable
fun KeyEditorRoute(
    navController: NavHostController,
    keyId: String?,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel {
        EditKeyViewModel(
            keyId = keyId,
            addSshPrivateKeyUseCase = container.addSshPrivateKeyUseCase,
            updateSshPrivateKeyUseCase = container.updateSshPrivateKeyUseCase,
            getSshPrivateKeyByIdUseCase = container.getSshPrivateKeyByIdUseCase,
            getSshConfigListUseCase = GetSshConfigListUseCase(container.sshConfigRepository),
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(state.isSaved) {
        if (!state.isSaved) return@LaunchedEffect
        // Saved while in the background: close (navigateBack needs this entry resumed) and toast once
        // the editor is back on screen.
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        toaster.show(if (state.isEditing) "Changes saved" else "Key saved")
        // The screen that opened the editor (Keys, or the server editor) learns which key was saved.
        navController.previousBackStackEntry?.savedStateHandle?.set(FRESH_KEY_ID, state.savedKeyId)
        navController.navigateBack()
    }

    KeyEditorScreen(
        state = state,
        onBack = { navController.navigateBack() },
        onSave = viewModel::save,
        onFormChange = viewModel::updateForm,
    )
}
