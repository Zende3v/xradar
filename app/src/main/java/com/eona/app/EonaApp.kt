package com.eona.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.preferences.AppPreferences
import com.eona.app.feature.onboarding.OnboardingRoute
import com.eona.app.feature.onboarding.Terms
import com.eona.app.feature.onboarding.TermsScreen
import com.eona.app.feature.permission.LocationPermissionRoute
import com.eona.app.feature.subscription.OffersPrompt
import com.eona.app.location.LocationServiceController
import com.eona.app.navigation.EonaNavHost
import com.eona.app.navigation.DeepLinks
import com.eona.app.feature.drive.FollowTripScreen
import com.eona.app.feature.drive.GroupWatchScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/**
 * App root. Nothing starts before the terms are accepted; then the onboarding/login screen; then
 * the location permission, asked when the map and the guidance need it; then [EonaNavHost].
 */
@Composable
fun EonaApp() {
    val context = LocalContext.current
    AppPreferences.init(context)
    AccountRepository.restore(context)
    LaunchedEffect(Unit) { AccountRepository.refresh(context) }
    val account by AccountRepository.account.collectAsStateWithLifecycle()
    val settings by AppPreferences.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // A blocked account sees the offers each time the app comes to the front, and as soon as it
    // gets blocked (the driving screen shows them).
    LifecycleStartEffect(Unit) {
        val job = scope.launch {
            AccountRepository.reload()
            OffersPrompt.offerIfRestricted()
        }
        onStopOrDispose { job.cancel() }
    }
    LaunchedEffect(account?.isRestricted) {
        if (account?.isRestricted == true) OffersPrompt.offerIfRestricted()
    }
    var proceed by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    when {
        // The condition of use of the app: nothing else before it.
        AppPreferences.needsTerms(settings, Terms.VERSION) -> TermsScreen()
        account?.isOnboarded != true -> OnboardingRoute()
        !proceed -> LocationPermissionRoute(onProceed = { proceed = true })
        else -> {
            LaunchedEffect(Unit) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                // Only start the foreground location service once the permission is real
                // (Android 14 forbids starting a location FGS without it).
                if (granted) LocationServiceController.start(context)
            }
            // A shared trip or a group link opened from outside: over the app, until closed.
            val follow by DeepLinks.follow.collectAsStateWithLifecycle()
            val watch by DeepLinks.watch.collectAsStateWithLifecycle()
            Box(Modifier.fillMaxSize()) {
                EonaNavHost()
                follow?.let { token -> FollowTripScreen(token, onClose = DeepLinks::closeFollow) }
                watch?.let { token -> GroupWatchScreen(token, onClose = DeepLinks::closeWatch) }
            }
        }
    }
}
