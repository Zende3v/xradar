package com.eona.app.feature.subscription

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eona.app.core.model.Account
import com.eona.app.designsystem.component.EonaBadge
import com.eona.app.designsystem.component.EonaCard
import com.eona.app.designsystem.component.EonaGlowTile
import com.eona.app.designsystem.component.EonaSurface
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.menu.accessLabel

/**
 * The offers, over a blocked action: why it is blocked, the plans, what membership brings.
 * No payment yet: the plans are shown, not sold. Place it last in a full-size box.
 */
@Composable
fun OffersSheet(reason: PaywallReason, account: Account?, onClose: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    BackHandler(onBack = onClose)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(spacing.md),
        contentAlignment = Alignment.BottomCenter,
    ) {
        EonaSurface(
            modifier = Modifier
                .fillMaxWidth()
                // Taps on the card stay on the card.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = EonaTheme.shapes.xl,
            color = colors.surfaceElevated,
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                EonaGlowTile(EonaIcons.Star, size = 72.dp, iconSize = 34.dp, shape = EonaTheme.shapes.xl)
                EonaText(
                    reason.title(account),
                    style = EonaTheme.typography.title,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                EonaText(
                    reason.message(account),
                    style = EonaTheme.typography.callout,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                EonaBadge(accessLabel(account), glow = true)
                SubscriptionPlans()
                EonaCard(modifier = Modifier.fillMaxWidth()) { MembershipBenefits() }
                EonaText(
                    "Le paiement dans l'app arrive bientôt.",
                    style = EonaTheme.typography.footnote,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(EonaTheme.shapes.lg)
                        .border(1.dp, colors.border, EonaTheme.shapes.lg)
                        .clickable(onClick = onClose)
                        .padding(vertical = spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    EonaText("Plus tard", style = EonaTheme.typography.bodyStrong, color = colors.textPrimary)
                }
            }
        }
    }
}
