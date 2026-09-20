package com.eona.app.feature.permission

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.eona.app.designsystem.component.EonaMessageState
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme

/**
 * Location rationale + real OS permission request. Kept intentionally lenient for
 * Phase 1 (the HUD runs on simulated data): whatever the user chooses, we proceed;
 * Phase 2 will branch on the actual result to drive real GPS.
 */
@Composable
fun LocationPermissionRoute(onProceed: () -> Unit) {
    // "Allow all the time" (background) can only be requested after foreground is granted.
    val backgroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ -> onProceed() }

    val foregroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            onProceed()
        }
    }

    LocationPermissionScreen(
        onAllow = {
            val permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
            foregroundLauncher.launch(permissions)
        },
        onSkip = onProceed,
    )
}

@Composable
fun LocationPermissionScreen(onAllow: () -> Unit, onSkip: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EonaTheme.colors.canvas)
            .safeDrawingPadding(),
    ) {
        EonaMessageState(
            icon = EonaIcons.Gps,
            iconTint = EonaTheme.colors.accent,
            title = "Activer la localisation",
            message = "EONA utilise ta position pour la navigation en temps réel et les alertes radars sur ta route. Ta position n'est jamais partagée sans ton accord.",
            primaryLabel = "Autoriser la localisation",
            onPrimary = onAllow,
            secondaryLabel = "Plus tard",
            onSecondary = onSkip,
        )
    }
}

@Preview(name = "Permission · dark", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun LocationPermissionPreview() {
    EonaTheme(darkTheme = true) { LocationPermissionScreen(onAllow = {}, onSkip = {}) }
}
