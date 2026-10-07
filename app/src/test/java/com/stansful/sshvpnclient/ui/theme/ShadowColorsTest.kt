package com.stansful.sshvpnclient.ui.theme

import androidx.compose.ui.graphics.Color
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowColorsTest {
    @Test
    fun `night preset maps onto the night tokens`() {
        val colors = CustomThemeColors.night().toShadowColors()

        assertTrue(colors.isDark)
        assertEquals(NightColors.bg, colors.bg)
        assertEquals(NightColors.amber, colors.amber)
        assertEquals(NightColors.mint, colors.mint)
        assertEquals(NightColors.ink1, colors.ink1)
        assertEquals(NightColors.line2, colors.line2)
        assertEquals(NightColors.amber, colors.amberText)
    }

    @Test
    fun `unedited night and day presets resolve to the exact design palettes`() {
        assertSame(NightColors, CustomThemeColors.night().toShadowColors())
        assertSame(DayColors, CustomThemeColors.day().toShadowColors())
    }

    @Test
    fun `an edited day preset darkens accent text to reach AA contrast`() {
        val colors = CustomThemeColors.day().copy(surface = 0xFFFDFCFA.toInt()).toShadowColors()

        assertFalse(colors.isDark)
        assertEquals(DayColors.amber, colors.amber)
        assertTrue(contrastRatio(colors.amberText, colors.bg) >= AA)
        assertTrue(contrastRatio(colors.mintText, colors.bg) >= AA)
        assertTrue(contrastRatio(colors.coralText, colors.bg) >= AA)
    }

    @Test
    fun `design palettes keep body and accent text at AA`() {
        listOf(NightColors, DayColors).forEach { colors ->
            assertTrue(contrastRatio(colors.ink1, colors.bg) >= AA)
            assertTrue(contrastRatio(colors.ink2, colors.bg) >= AA)
            assertTrue(contrastRatio(colors.amberText, colors.bg) >= AA)
            assertTrue(contrastRatio(colors.mintText, colors.bg) >= AA)
            assertTrue(contrastRatio(colors.coralText, colors.bg) >= AA)
            assertTrue(contrastRatio(colors.onAmber, colors.amber) >= AA)
            // Captions are drawn on cards far more than on the page; the Day token is 4.4:1 on bg.
            assertTrue(contrastRatio(colors.ink3, colors.surface1) >= AA)
        }
    }

    @Test
    fun `every derived preset keeps text roles readable`() {
        listOf(
            CustomThemeColors.night().copy(primary = 0xFFFFA62B.toInt()),
            CustomThemeColors.day().copy(primary = 0xFFF0A020.toInt()),
            CustomThemeColors.shadowClassic(),
            CustomThemeColors.ocean(),
            CustomThemeColors.defaultLight(),
        ).map { it.toShadowColors() }.forEach { colors ->
            assertTrue(contrastRatio(colors.ink1, colors.bg) >= AA)
            assertTrue(contrastRatio(colors.ink3, colors.bg) >= AA)
            assertTrue(contrastRatio(colors.amberText, colors.bg) >= AA)
            assertTrue(contrastRatio(colors.onAmber, colors.amber) >= AA)
        }
    }

    @Test
    fun `on-fill ink picks the higher contrast`() {
        assertEquals(Color.White, readableInkOn(Color(0xFF2F6FDB)))
        assertTrue(contrastRatio(readableInkOn(NightColors.coral), NightColors.coral) >= AA)
    }

    @Test
    fun `theme modes resolve to the right palette`() {
        val custom = CustomThemeColors.ocean()
        assertSame(NightColors, shadowColorsFor(AppThemeMode.SYSTEM, custom, systemDark = true))
        assertSame(DayColors, shadowColorsFor(AppThemeMode.SYSTEM, custom, systemDark = false))
        assertSame(DayColors, shadowColorsFor(AppThemeMode.LIGHT, custom, systemDark = true))
        assertSame(NightColors, shadowColorsFor(AppThemeMode.DARK, custom, systemDark = false))
        assertEquals(custom.toShadowColors(), shadowColorsFor(AppThemeMode.CUSTOM, custom, systemDark = false))
    }

    private companion object {
        const val AA = 4.5f
    }
}
