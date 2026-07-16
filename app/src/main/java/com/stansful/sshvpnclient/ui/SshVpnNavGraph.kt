package com.stansful.sshvpnclient.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.ui.apppicker.AppPickerRoute
import com.stansful.sshvpnclient.ui.configedit.EditConfigRoute
import com.stansful.sshvpnclient.ui.configs.ConfigListRoute
import com.stansful.sshvpnclient.ui.keyedit.EditKeyRoute
import com.stansful.sshvpnclient.ui.keys.KeyListRoute
import com.stansful.sshvpnclient.ui.main.MainRoute

object Routes {
    const val MAIN = "main"
    const val CONFIGS = "configs"
    const val KEYS = "keys"
    const val EDIT_CONFIG = "edit-config"
    const val EDIT_KEY = "edit-key"
    const val APP_PICKER = "app-picker"

    fun editConfig(configId: String? = null) = "$EDIT_CONFIG/${configId ?: NEW_ID}"
    fun editKey(keyId: String? = null) = "$EDIT_KEY/${keyId ?: NEW_ID}"

    const val NEW_ID = "new"
}

@Composable
fun SshVpnNavGraph(
    container: AppContainer,
    navController: NavHostController,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.MAIN,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = {
            fadeIn(tween(160)) + slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                tween(220),
            )
        },
        exitTransition = {
            fadeOut(tween(120))
        },
        popEnterTransition = {
            fadeIn(tween(160)) + slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                tween(220),
            )
        },
        popExitTransition = {
            fadeOut(tween(120))
        },
    ) {
        composable(Routes.MAIN) {
            MainRoute(
                container = container,
                openConfigs = { navController.navigate(Routes.CONFIGS) },
                openKeys = { navController.navigate(Routes.KEYS) },
                openAppPicker = { navController.navigate(Routes.APP_PICKER) },
            )
        }
        composable(Routes.APP_PICKER) {
            AppPickerRoute(
                container = container,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CONFIGS) {
            ConfigListRoute(
                container = container,
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(Routes.editConfig()) },
                onEdit = { navController.navigate(Routes.editConfig(it)) },
            )
        }
        composable("${Routes.EDIT_CONFIG}/{configId}") { backStackEntry ->
            val rawId = backStackEntry.arguments?.getString("configId")
            EditConfigRoute(
                container = container,
                configId = rawId?.takeUnless { it == Routes.NEW_ID },
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
                onAddKey = { navController.navigate(Routes.editKey()) },
            )
        }
        composable(Routes.KEYS) {
            KeyListRoute(
                container = container,
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(Routes.editKey()) },
                onEdit = { navController.navigate(Routes.editKey(it)) },
            )
        }
        composable("${Routes.EDIT_KEY}/{keyId}") { backStackEntry ->
            val rawId = backStackEntry.arguments?.getString("keyId")
            EditKeyRoute(
                container = container,
                keyId = rawId?.takeUnless { it == Routes.NEW_ID },
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
    }
}
