package com.stansful.sshvpnclient.screenshots.settings

import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.screenshots.ScreenSize
import com.stansful.sshvpnclient.screenshots.ScreenshotTest
import com.stansful.sshvpnclient.screenshots.renderScreenshot
import com.stansful.sshvpnclient.ui.settings.AppearanceActions
import com.stansful.sshvpnclient.ui.settings.AppearanceScreen
import com.stansful.sshvpnclient.ui.settings.AppearanceUiState
import com.stansful.sshvpnclient.ui.settings.PaletteRole
import com.stansful.sshvpnclient.ui.theme.DayColors
import com.stansful.sshvpnclient.ui.theme.NightColors
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.toShadowColors
import org.junit.Test

class AppearanceScreenshotTest : ScreenshotTest() {
    private val classic = CustomThemeColors.shadowClassic()
    private val saved = AppearanceUiState(
        themeMode = AppThemeMode.CUSTOM,
        saved = classic,
        draft = classic,
        openRole = PaletteRole.Primary,
    )
    private val edited = saved.copy(draft = classic.copy(primary = 0xFFFFB547.toInt(), onSurface = 0xFF54463A.toInt()))
    private val tall = ScreenSize(390, 1240)

    private fun render(
        name: String,
        state: AppearanceUiState,
        colors: ShadowColors = NightColors,
        size: ScreenSize = tall,
    ) {
        renderScreenshot(name, size, colors) { AppearanceScreen(state = state, actions = AppearanceActions()) }
    }

    @Test
    fun custom() {
        render("Appearance_CustomSaved_Night", saved)
        render("Appearance_CustomEdited_Night", edited)
        render("Appearance_CustomSaved_Day", saved, DayColors)
        render("Appearance_CustomSaved_Classic", saved, classic.toShadowColors())
        render("Appearance_CustomClosed_Phone_Night", saved.copy(openRole = null), size = ScreenSize.PHONE)
    }

    @Test
    fun otherThemes() {
        render("Appearance_System_Night", saved.copy(themeMode = AppThemeMode.SYSTEM), size = ScreenSize.PHONE)
        render("Appearance_DarkEdited_Night", edited.copy(themeMode = AppThemeMode.DARK), size = ScreenSize.PHONE)
        render("Appearance_Light_Day", saved.copy(themeMode = AppThemeMode.LIGHT), DayColors, ScreenSize.PHONE)
    }

    @Test
    fun discardDialog() {
        render(
            name = "Appearance_Discard_Night",
            state = edited.copy(openRole = null, showDiscardDialog = true),
            size = ScreenSize.PHONE,
        )
    }
}
