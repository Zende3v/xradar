package com.xradar.app.feature.subscription

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.xradar.app.R
import com.xradar.app.core.model.SubscriptionPlan
import com.xradar.app.designsystem.component.XRadarBadge
import com.xradar.app.designsystem.component.XRadarCard
import com.xradar.app.designsystem.component.XRadarGlowTile
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme

/** The plans side by side. */
@Composable
fun SubscriptionPlans(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.md),
    ) {
        SubscriptionPlan.ALL.forEach { plan -> PlanCard(plan, Modifier.weight(1f)) }
    }
}

/** One plan: its name, price and period; the longer plan with its saving, outlined. */
@Composable
private fun PlanCard(plan: SubscriptionPlan, modifier: Modifier = Modifier) {
    val colors = XRadarTheme.colors
    val saving = plan.savingLabel
    XRadarCard(
        modifier = modifier,
        border = BorderStroke(if (saving != null) 2.dp else 1.dp, if (saving != null) colors.accent else colors.border),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.sm)) {
            XRadarText(plan.title, style = XRadarTheme.typography.headline, color = colors.textPrimary, maxLines = 1)
            if (saving != null) XRadarBadge(saving, color = colors.success)
            Row(verticalAlignment = Alignment.Bottom) {
                XRadarText(plan.priceLabel, style = XRadarTheme.typography.title, color = colors.textPrimary, maxLines = 1)
                XRadarText(plan.periodLabel, style = XRadarTheme.typography.subhead, color = colors.textSecondary, maxLines = 1)
            }
            XRadarText(plan.perMonthLabel ?: "Chaque mois", style = XRadarTheme.typography.footnote, color = colors.textTertiary)
        }
    }
}

/** What membership brings, ticked. */
@Composable
fun MembershipBenefits(modifier: Modifier = Modifier) {
    val colors = XRadarTheme.colors
    val benefits = listOf(
        XRadarIcons.Navigation to "Navigation guidée sans limite",
        XRadarIcons.Radar to "Alertes radars et dangers",
        XRadarIcons.Warning to "Signalements sans limite",
        ImageVector.vectorResource(R.drawable.ic_music) to "Musique au volant",
        XRadarIcons.User to "Photo de profil",
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.md)) {
        benefits.forEach { (icon, title) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.md),
            ) {
                XRadarGlowTile(icon)
                XRadarText(title, style = XRadarTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                XRadarIcon(XRadarIcons.Check, contentDescription = null, tint = colors.success, size = 18.dp)
            }
        }
    }
}
