package com.stansful.sshvpnclient.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.R

/** Onest (variable font) — every UI string. */
@OptIn(ExperimentalTextApi::class)
val OnestFamily: FontFamily = FontFamily(
    listOf(400, 500, 600, 700).map { weight ->
        Font(
            resId = R.font.onest,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    },
)

/** JetBrains Mono (variable font) — hosts, ports, URIs, keys, fingerprints, logs, latency numbers. */
@OptIn(ExperimentalTextApi::class)
val JetBrainsMonoFamily: FontFamily = FontFamily(
    listOf(400, 500).map { weight ->
        Font(
            resId = R.font.jetbrains_mono,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    },
)

/**
 * Type scale of the "shadow" design (BRIEF §3). Use through `Shadow.type`.
 * `overline` does not transform case: pass upper-cased text (SectionHeader does it for you).
 */
@Immutable
data class ShadowType(
    /** 32/38 600 -0.02em — phone screen titles. */
    val display: TextStyle,
    /** 24/30 600 -0.015em — sheet titles, tablet pane titles. */
    val titleL: TextStyle,
    /** 20/26 600 -0.01em — status headline, dialog titles. */
    val titleM: TextStyle,
    /** 16/22 600 — card titles, row titles, sub-screen bar titles. */
    val titleS: TextStyle,
    /** 15/22 400. */
    val body: TextStyle,
    /** 15/22 500. */
    val bodyMedium: TextStyle,
    /** 15/20 500 — list row titles, menu items. */
    val rowTitle: TextStyle,
    /** 13/18 400 — helper text, sublines. */
    val bodyS: TextStyle,
    /** 12/16 400 — field helpers, captions. */
    val caption: TextStyle,
    /** 13/18 600 — small buttons, pills, chips. */
    val label: TextStyle,
    /** 14/18 600 — segmented control labels. */
    val segment: TextStyle,
    /** 15/20 600 — buttons. */
    val button: TextStyle,
    /** 11/14 600 +0.08em — section headers (upper-case the text yourself). */
    val overline: TextStyle,
    /** 12/16 600 — bottom nav / rail labels. */
    val navLabel: TextStyle,
    /** 13/18 400 mono. */
    val mono: TextStyle,
    /** 13/18 500 mono — latency numbers, emphasized data. */
    val monoMedium: TextStyle,
    /** 12/16 400 mono — sublines (hosts, protocols). */
    val monoS: TextStyle,
    /** 14/20 500 mono — text inside mono input fields. */
    val monoInput: TextStyle,
    /** 22/28 700 -0.03em — the "shadow" wordmark. */
    val wordmark: TextStyle,
)

private val CenteredLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun onest(
    size: Int,
    lineHeight: Int,
    weight: Int,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = OnestFamily,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing,
    lineHeightStyle = CenteredLineHeight,
)

private fun mono(size: Int, lineHeight: Int, weight: Int) = TextStyle(
    fontFamily = JetBrainsMonoFamily,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    lineHeightStyle = CenteredLineHeight,
)

val DefaultShadowType: ShadowType = ShadowType(
    display = onest(32, 38, 600, (-0.02).em),
    titleL = onest(24, 30, 600, (-0.015).em),
    titleM = onest(20, 26, 600, (-0.01).em),
    titleS = onest(16, 22, 600),
    body = onest(15, 22, 400),
    bodyMedium = onest(15, 22, 500),
    rowTitle = onest(15, 20, 500),
    bodyS = onest(13, 18, 400),
    caption = onest(12, 16, 400),
    label = onest(13, 18, 600),
    segment = onest(14, 18, 600),
    button = onest(15, 20, 600),
    overline = onest(11, 14, 600, 0.08.em),
    navLabel = onest(12, 16, 600),
    mono = mono(13, 18, 400),
    monoMedium = mono(13, 18, 500),
    monoS = mono(12, 16, 400),
    monoInput = mono(14, 20, 500),
    wordmark = onest(22, 28, 700, (-0.03).em),
)

val LocalShadowType: ProvidableCompositionLocal<ShadowType> = staticCompositionLocalOf { DefaultShadowType }
