package com.xradar.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xradar.app.feature.diagnostic.DiagnosticRoute
import com.xradar.app.feature.drive.DriveRoute
import com.xradar.app.feature.menu.LegalRoute
import com.xradar.app.feature.menu.MenuRoute
import com.xradar.app.feature.menu.ReferralRoute
import com.xradar.app.feature.menu.StatsRoute
import com.xradar.app.feature.profile.ProfileRoute
import com.xradar.app.feature.search.SearchRoute
import com.xradar.app.feature.settings.SettingsRoute
import com.xradar.app.feature.subscription.SubscriptionRoute

private const val TRANSITION_MS = 300
private const val SEARCH_FADE_MS = 250

/**
 * App navigation graph. The driving HUD is the start destination (full-screen); the search lies
 * over it, see-through; the gear opens the Menu, whose sections push over it with an iOS-like
 * slide.
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
            // The search over the HUD, frosted: the map and the HUD show through it (like iOS).
            var searchOpen by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = searchOpen) { searchOpen = false }
            Box(Modifier.fillMaxSize()) {
                DriveRoute(
                    onOpenSearch = { searchOpen = true },
                    onOpenSettings = { navController.navigate(Routes.MENU) },
                    modifier = if (searchOpen) Modifier.clearAndSetSemantics { } else Modifier,
                )
                AnimatedVisibility(
                    visible = searchOpen,
                    enter = fadeIn(tween(SEARCH_FADE_MS)),
                    exit = fadeOut(tween(SEARCH_FADE_MS)),
                ) {
                    SearchRoute(onBack = { searchOpen = false })
                }
            }
        }
        composable(Routes.MENU) {
            MenuRoute(
                onBack = { navController.popBackStack() },
                onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                onOpenSubscription = { navController.navigate(Routes.SUBSCRIPTION) },
                onOpenStats = { navController.navigate(Routes.STATS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenReferral = { navController.navigate(Routes.REFERRAL) },
                onOpenLegal = { navController.navigate(Routes.LEGAL) },
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
        composable(Routes.SUBSCRIPTION) {
            SubscriptionRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.LEGAL) {
            LegalRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.DIAGNOSTIC) {
            DiagnosticRoute(onBack = { navController.popBackStack() })
        }
    }
}
