package com.stansful.sshvpnclient.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Motion tokens (BRIEF §7, Motion.dc.html). Durations in milliseconds. */
object ShadowMotion {
    /** Tap feedback (press scale). */
    const val Press = 120

    /** Small state changes: toggles, chips, pill text. */
    const val Small = 200

    /** Surfaces: sheets, dialogs, expand/collapse. */
    const val Surface = 340

    /** State color / glow changes. */
    const val State = 500

    /** Sheet rise. */
    const val SheetEnter = 380

    /** Sheet / scrim drop-away. */
    const val SheetExit = 260

    /** Dialog scale-in. */
    const val Dialog = 320

    /** Segmented thumb slide. */
    const val Thumb = 360

    /** Color crossfades of pills, dots, orb border. */
    const val ColorFade = 400

    /** Orb arc fill on connect, halo fade. */
    const val Glow = 600

    /** Screen / row entrance (fade + 12 dp rise). */
    const val FadeUp = 360

    /** Text swap (fade + 6 dp rise). */
    const val Swap = 260

    /** Stagger step between list rows. */
    const val StaggerStep = 30

    /** Rows beyond this index start with the same delay. */
    const val StaggerMax = 8

    /** Orb spinner revolution. */
    const val Spinner = 1300

    /** Halo breathing period. */
    const val Breathe = 1900

    /** Calm halo breathing while Auto waits to retry. */
    const val BreatheCalm = 2400

    /** Progress dot blink period. */
    const val Blink = 1200

    /** One-shot connect ripple. */
    const val Ripple = 1100

    /** One-shot error shake. */
    const val Shake = 480

    /** Toast auto-hide delay. */
    const val ToastVisible = 2500

    /** Press scale of buttons, chips and cards. */
    const val PressScale = 0.97f

    /** Press scale of the connection orb. */
    const val OrbPressScale = 0.96f

    /** Enter, expand, screen push. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

    /** Close, back, dismiss. */
    val Exit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** Thumb, toggles, dialogs (slight overshoot). */
    val Spring: Easing = CubicBezierEasing(0.34f, 1.3f, 0.64f, 1f)

    /** CSS `ease`. */
    val Ease: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** CSS `ease-in-out`. */
    val EaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** Error shake curve. */
    val ShakeEasing: Easing = CubicBezierEasing(0.36f, 0.07f, 0.19f, 0.97f)
}

/** True when the system "Remove animations" setting is on: loops stop, transitions cut in place. */
val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** Reads `Settings.Global.ANIMATOR_DURATION_SCALE == 0` and follows changes while composed. */
@Composable
fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var reduced by remember { mutableStateOf(readReducedMotion(context.contentResolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = readReducedMotion(resolver)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private fun readReducedMotion(resolver: android.content.ContentResolver): Boolean {
    return Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

/** A tween that collapses to `snap()` when reduced motion is on. */
@Composable
@ReadOnlyComposable
fun <T> shadowTween(
    durationMillis: Int,
    easing: Easing = ShadowMotion.Standard,
    delayMillis: Int = 0,
): FiniteAnimationSpec<T> {
    return if (LocalReducedMotion.current) {
        snap()
    } else {
        tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = easing)
    }
}
