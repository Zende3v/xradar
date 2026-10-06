package com.eona.app.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.theme.EonaTheme

/**
 * Invité : création de compte ou connexion par-dessus l'écran courant (iOS OnboardingView
 * converting). « Fermer » en haut à droite. À placer en dernier dans une boîte plein écran.
 */
@Composable
fun ConvertingOverlay(onClose: () -> Unit) {
    val spacing = EonaTheme.spacing
    BackHandler(onBack = onClose)
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        OnboardingScreen(converting = true)
        EonaText(
            "Fermer",
            style = EonaTheme.typography.bodyStrong,
            color = EonaTheme.colors.textSecondary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(spacing.lg)
                .clip(EonaTheme.shapes.pill)
                .clickable(role = Role.Button, onClick = onClose)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        )
    }
}
