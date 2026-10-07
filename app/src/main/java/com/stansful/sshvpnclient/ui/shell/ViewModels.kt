package com.stansful.sshvpnclient.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import kotlin.reflect.KClass

/** The app's dependency container, provided by [ShadowApp]. */
val LocalAppContainer: ProvidableCompositionLocal<AppContainer> = staticCompositionLocalOf {
    error("LocalAppContainer is provided by ShadowApp")
}

/** The Activity's [ViewModelStoreOwner], captured by [ShadowApp] above the NavHost. */
internal val LocalActivityViewModelStoreOwner: ProvidableCompositionLocal<ViewModelStoreOwner?> =
    staticCompositionLocalOf { null }

/**
 * A ViewModel shared by every screen of the Activity (one instance for Home, Routes, Settings,
 * Terminal…): `MainViewModel`, `SmartConnectViewModel`, `OpenSourceViewModel`. Created with
 * [AppViewModelFactory].
 */
@Composable
inline fun <reified T : ViewModel> activityViewModel(): T = activityViewModel(T::class)

/** Non-reified form of [activityViewModel]. */
@Composable
fun <T : ViewModel> activityViewModel(modelClass: KClass<T>): T {
    val owner = LocalActivityViewModelStoreOwner.current
        ?: checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner" }
    val container = LocalAppContainer.current
    val factory = remember(container) { AppViewModelFactory(container) }
    return viewModel(modelClass = modelClass, viewModelStoreOwner = owner, factory = factory)
}

/**
 * A ViewModel scoped to the current navigation entry that [AppViewModelFactory] can build (the Servers
 * list, the App routing picker). The editors and the Keys list build theirs at their routes.
 */
@Composable
inline fun <reified T : ViewModel> screenViewModel(): T = screenViewModel(T::class)

/** Non-reified form of [screenViewModel]. */
@Composable
fun <T : ViewModel> screenViewModel(modelClass: KClass<T>): T {
    val owner = checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner" }
    val container = LocalAppContainer.current
    val factory = remember(container) { AppViewModelFactory(container) }
    return viewModel(modelClass = modelClass, viewModelStoreOwner = owner, factory = factory)
}
