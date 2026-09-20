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
import com.eona.app.feature.permission.LocationPermissionRoute
import com.eona.app.feature.subscription.OffersPrompt
import com.eona.app.location.LocationServiceController
import com.eona.app.navigation.EonaNavHost

/**
 * App root. Gates the navigation graph behind the location-permission screen, then
 * the onboarding/login screen; once past both it shows the main [EonaNavHost].
 */
@Composable
fun EonaApp() {
    val context = LocalContext.current
    AppPreferences.init(context)
    AccountRepository.restore(context)
    LaunchedEffect(Unit) { AccountRepository.refresh(context) }
    val account by AccountRepository.account.collectAsStateWithLifecycle()
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
        !proceed -> LocationPermissionRoute(onProceed = { proceed = true })
        account?.isOnboarded != true -> OnboardingRoute()
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
            EonaNavHost()
        }
    }
}
