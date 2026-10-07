package com.stansful.sshvpnclient.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stansful.sshvpnclient.ui.designsystem.LocalToaster
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ToastTone
import com.stansful.sshvpnclient.ui.shell.LocalAppContainer
import com.stansful.sshvpnclient.ui.shell.navigateBack

/**
 * Appearance: theme mode (applies at once) and the custom palette editor. The draft survives rotation
 * and theme switches; only Apply saves it, and leaving with a changed draft asks first.
 */
@Composable
fun AppearanceRoute(
    navController: NavHostController,
) {
    val repository = LocalAppContainer.current.appSettingsRepository
    val settings by repository.settings.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val saved = settings.customThemeColors
    var draft by rememberSaveable(stateSaver = CustomThemeColorsSaver) { mutableStateOf(saved) }
    var openRoleName by rememberSaveable { mutableStateOf<String?>(null) }
    var askDiscard by remember { mutableStateOf(false) }
    val dirty = draft != saved

    BackHandler(enabled = dirty) { askDiscard = true }

    AppearanceScreen(
        state = AppearanceUiState(
            themeMode = settings.themeMode,
            saved = saved,
            draft = draft,
            openRole = openRoleName?.let(PaletteRole::valueOf),
            showDiscardDialog = askDiscard && dirty,
        ),
        actions = AppearanceActions(
            onBack = { if (dirty) askDiscard = true else navController.navigateBack() },
            onThemeModeChange = repository::setThemeMode,
            onPreset = { draft = it },
            onToggleRole = { role -> openRoleName = role.name.takeUnless { it == openRoleName } },
            onRoleColorChange = { role, color -> draft = draft.with(role, color) },
            onRevert = { draft = saved },
            onApply = {
                if (draft != saved) {
                    repository.setCustomThemeColors(draft)
                    toaster.show(
                        message = "Palette applied to the whole app",
                        tone = ToastTone.Success,
                        icon = ShadowIcons.CheckCircle,
                    )
                }
            },
            onDiscard = {
                askDiscard = false
                draft = saved
                navController.navigateBack()
            },
            onKeepEditing = { askDiscard = false },
        ),
    )
}
