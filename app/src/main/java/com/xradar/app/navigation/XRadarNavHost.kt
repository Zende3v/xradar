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
import com.xradar.app.feature.menu.MenuRoute
import com.xradar.app.feature.menu.ReferralRoute
import com.xradar.app.feature.menu.StatsRoute
import com.xradar.app.feature.profile.ProfileRoute
import com.xradar.app.feature.search.SearchRoute
import com.xradar.app.feature.settings.SettingsRoute

private const val TRANSITION_MS = 300

/**
 * App navigation graph. The driving HUD is the start destination (full-screen);
 * the gear opens the Menu, whose sections push over it with an iOS-like slide.
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
                onOpenSettings = { navController.navigate(Routes.MENU) },
            )
        }
        composable(Routes.SEARCH) {
            SearchRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.MENU) {
            MenuRoute(
                onBack = { navController.popBackStack() },
                onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                onOpenStats = { navController.navigate(Routes.STATS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenReferral = { navController.navigate(Routes.REFERRAL) },
            )
        }
        composable(Routes.ACCOUNT) {
            ProfileRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.STATS) {
            StatsRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                onBack = { navController.popBackStack() },
                onOpenDiagnostic = { navController.navigate(Routes.DIAGNOSTIC) },
            )
        }
        composable(Routes.REFERRAL) {
            ReferralRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.DIAGNOSTIC) {
            DiagnosticRoute(onBack = { navController.popBackStack() })
        }
    }
}
