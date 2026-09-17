package com.xradar.app.feature.subscription

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
import com.xradar.app.core.model.Account
import com.xradar.app.designsystem.component.XRadarBadge
import com.xradar.app.designsystem.component.XRadarCard
import com.xradar.app.designsystem.component.XRadarGlowTile
import com.xradar.app.designsystem.component.XRadarSurface
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme
import com.xradar.app.feature.menu.accessLabel

/**
 * The offers, over a blocked action: why it is blocked, the plans, what membership brings.
 * No payment yet: the plans are shown, not sold. Place it last in a full-size box.
 */
@Composable
fun OffersSheet(reason: PaywallReason, account: Account?, onClose: () -> Unit) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
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
        XRadarSurface(
            modifier = Modifier
                .fillMaxWidth()
                // Taps on the card stay on the card.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = XRadarTheme.shapes.xl,
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
                XRadarGlowTile(XRadarIcons.Star, size = 72.dp, iconSize = 34.dp, shape = XRadarTheme.shapes.xl)
                XRadarText(
                    reason.title(account),
                    style = XRadarTheme.typography.title,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                XRadarText(
                    reason.message(account),
                    style = XRadarTheme.typography.callout,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                XRadarBadge(accessLabel(account), glow = true)
                SubscriptionPlans()
                XRadarCard(modifier = Modifier.fillMaxWidth()) { MembershipBenefits() }
                XRadarText(
                    "Le paiement dans l'app arrive bientôt.",
                    style = XRadarTheme.typography.footnote,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(XRadarTheme.shapes.lg)
                        .border(1.dp, colors.border, XRadarTheme.shapes.lg)
                        .clickable(onClick = onClose)
                        .padding(vertical = spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    XRadarText("Plus tard", style = XRadarTheme.typography.bodyStrong, color = colors.textPrimary)
                }
            }
        }
    }
}
