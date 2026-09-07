package com.xradar.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xradar.app.feature.diagnostic.DiagnosticRoute
import com.xradar.app.feature.drive.DriveRoute
import com.xradar.app.feature.history.HistoryRoute
import com.xradar.app.feature.profile.ProfileRoute
import com.xradar.app.feature.search.SearchRoute
import com.xradar.app.feature.settings.SettingsRoute

private const val TRANSITION_MS = 300

/**
 * App navigation graph. The driving HUD is the start destination (full-screen);
 * secondary screens push over it with an iOS-like horizontal slide.
 */
@Composable
fun XRadarNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.DRIVE,
        modifier = modifier,
        enterTransition = { slideIntoContainer(SlideDirection.Start, tween(TRANSITION_MS)) },
        exitTransition = { slideOutOfContainer(SlideDirection.Start, tween(TRANSITION_MS)) },
        popEnterTransition = { slideIntoContainer(SlideDirection.End, tween(TRANSITION_MS)) },
        popExitTransition = { slideOutOfContainer(SlideDirection.End, tween(TRANSITION_MS)) },
    ) {
        composable(Routes.DRIVE) {
            DriveRoute(
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SEARCH) {
            SearchRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.HISTORY) {
            HistoryRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.PROFILE) {
            ProfileRoute(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                onBack = { navController.popBackStack() },
                onOpenProfile = { navController.navigate(Routes.PROFILE) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenDiagnostic = { navController.navigate(Routes.DIAGNOSTIC) },
            )
        }
        composable(Routes.DIAGNOSTIC) {
            DiagnosticRoute(onBack = { navController.popBackStack() })
        }
    }
}
