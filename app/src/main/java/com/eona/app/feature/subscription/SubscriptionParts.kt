package com.eona.app.feature.subscription

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
import com.eona.app.R
import com.eona.app.core.model.SubscriptionPlan
import com.eona.app.designsystem.component.EonaBadge
import com.eona.app.designsystem.component.EonaCard
import com.eona.app.designsystem.component.EonaGlowTile
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme

/** The plans side by side. */
@Composable
fun SubscriptionPlans(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md),
    ) {
        SubscriptionPlan.ALL.forEach { plan -> PlanCard(plan, Modifier.weight(1f)) }
    }
}

/** One plan: its name, price and period; the longer plan with its saving, outlined. */
@Composable
private fun PlanCard(plan: SubscriptionPlan, modifier: Modifier = Modifier) {
    val colors = EonaTheme.colors
    val saving = plan.savingLabel
    EonaCard(
        modifier = modifier,
        border = BorderStroke(if (saving != null) 2.dp else 1.dp, if (saving != null) colors.accent else colors.border),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm)) {
            EonaText(plan.title, style = EonaTheme.typography.headline, color = colors.textPrimary, maxLines = 1)
            if (saving != null) EonaBadge(saving, color = colors.success)
            Row(verticalAlignment = Alignment.Bottom) {
                EonaText(plan.priceLabel, style = EonaTheme.typography.title, color = colors.textPrimary, maxLines = 1)
                EonaText(plan.periodLabel, style = EonaTheme.typography.subhead, color = colors.textSecondary, maxLines = 1)
            }
            EonaText(plan.perMonthLabel ?: "Chaque mois", style = EonaTheme.typography.footnote, color = colors.textTertiary)
        }
    }
}

/** Ce qu'EONA + ouvre, coché : seulement ce que l'app débloque vraiment. */
@Composable
fun MembershipBenefits(modifier: Modifier = Modifier) {
    val colors = EonaTheme.colors
    val benefits = listOf(
        Triple(EonaIcons.Navigation, "Navigation illimitée", "Guidage vocal, trafic en direct"),
        Triple(EonaIcons.Radar, "Alertes radars et dangers", "Fixes, mobiles, signalés par la communauté"),
        Triple(EonaIcons.Warning, "Signalements illimités", "Préviens les autres conducteurs"),
        Triple(ImageVector.vectorResource(R.drawable.ic_music), "Musique au volant", "Ta musique en un geste"),
        Triple(EonaIcons.User, "Profil complet", "Photo et pseudo"),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md)) {
        benefits.forEach { (icon, title, detail) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md),
            ) {
                EonaGlowTile(icon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    EonaText(title, style = EonaTheme.typography.body, color = colors.textPrimary)
                    EonaText(detail, style = EonaTheme.typography.footnote, color = colors.textTertiary)
                }
                EonaIcon(EonaIcons.Check, contentDescription = null, tint = colors.success, size = 18.dp)
            }
        }
    }
}
