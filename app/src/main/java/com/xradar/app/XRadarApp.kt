package com.xradar.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xradar.app.data.account.AccountRepository
import com.xradar.app.data.preferences.AppPreferences
import com.xradar.app.feature.onboarding.OnboardingRoute
import com.xradar.app.feature.permission.LocationPermissionRoute
import com.xradar.app.location.LocationServiceController
import com.xradar.app.navigation.XRadarNavHost

/**
 * App root. Gates the navigation graph behind the location-permission screen, then
 * the onboarding/login screen; once past both it shows the main [XRadarNavHost].
 */
@Composable
fun XRadarApp() {
    val context = LocalContext.current
    AppPreferences.init(context)
    AccountRepository.restore(context)
    LaunchedEffect(Unit) { AccountRepository.refresh(context) }
    val account by AccountRepository.account.collectAsStateWithLifecycle()
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
            XRadarNavHost()
        }
    }
}
