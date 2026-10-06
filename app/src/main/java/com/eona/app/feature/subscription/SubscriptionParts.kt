package com.eona.app.feature.subscription

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.eona.app.R
import com.eona.app.core.model.SubscriptionPlan
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme

/** Un avantage EONA+ : ligne du résumé, fiche de détail, valeurs Gratuit / EONA+ (iOS MembershipBenefit). */
data class MembershipBenefit(
    val title: String,
    val detail: String,
    val icon: @Composable () -> ImageVector,
    val color: Color,
    val free: String,
    val plus: String,
) {
    companion object {
        val ALL = listOf(
            MembershipBenefit("Trajets illimités", "Lancez vos trajets sans limite quotidienne.", { EonaIcons.TurnRight }, EonaPlusStyle.lavender, "4 / jour", "Illimités"),
            MembershipBenefit("Trajets intelligents", "Itinéraire selon vos habitudes et préférences de conduite.", { EonaIcons.Sparkles }, EonaPlusStyle.pink, "1 / jour", "Illimités"),
            MembershipBenefit("EONA Taxi", "Empruntez les voies réservées et les accès autorisés aux taxis.", { EonaIcons.CarSide }, EonaPlusStyle.peach, "Verrouillé", "Inclus"),
            MembershipBenefit("EONA Poids lourd", "Suivez des itinéraires adaptés au gabarit et aux restrictions de votre véhicule.", { EonaIcons.Truck }, EonaPlusStyle.sky, "Verrouillé", "Inclus"),
            MembershipBenefit("Trajets en groupe", "Partagez votre position et votre heure d'arrivée avec un groupe de cinq conducteurs maximum.", { EonaIcons.People }, EonaPlusStyle.mint, "Verrouillé", "Inclus"),
            MembershipBenefit("Thème", "Choisissez parmi 13 thèmes pour votre interface, votre tracé et votre véhicule.", { EonaIcons.Palette }, EonaPlusStyle.pink, "Cyan", "13 thèmes"),
            MembershipBenefit("Feux en direct", "Consultez le temps restant avant le changement des feux sur votre trajet.", { ImageVector.vectorResource(R.drawable.ic_eona_traffic_light) }, EonaPlusStyle.peach, "Verrouillé", "Inclus"),
        )
    }
}

/** Avantages visibles ; chaque ligne ouvre la fiche de l'offre, sans activer la fonction. */
@Composable
fun MembershipSummary(onOpen: (MembershipBenefit) -> Unit) {
    EonaPlusGroup {
        MembershipBenefit.ALL.forEachIndexed { index, benefit ->
            BenefitRow(benefit) { onOpen(benefit) }
            if (index < MembershipBenefit.ALL.lastIndex) EonaPlusDivider(Modifier.padding(start = 68.dp))
        }
    }
}

@Composable
private fun BenefitTile(benefit: MembershipBenefit, tile: androidx.compose.ui.unit.Dp, icon: androidx.compose.ui.unit.Dp, radius: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(tile)
            .clip(RoundedCornerShape(radius))
            .background(benefit.color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        EonaIcon(benefit.icon(), contentDescription = null, tint = benefit.color, size = icon)
    }
}

@Composable
private fun BenefitRow(benefit: MembershipBenefit, onClick: () -> Unit) {
    val spacing = EonaTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(role = Role.Button, onClickLabel = "Afficher le détail de l'offre", onClick = onClick)
            .padding(horizontal = spacing.lg, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        BenefitTile(benefit, 40.dp, 20.dp, 12.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            EonaText(benefit.title, style = EonaTheme.typography.bodyStrong, color = EonaPlusStyle.primary)
            EonaText(benefit.detail, style = EonaTheme.typography.footnote, color = EonaPlusStyle.secondary)
        }
        EonaIcon(EonaIcons.ChevronRight, contentDescription = null, tint = EonaPlusStyle.muted, size = 16.dp)
    }
}

/**
 * Fiche d'un avantage, du bas de l'écran : pictogramme, titre, détail, Gratuit et EONA+. À placer
 * en dernier dans une boîte plein écran.
 */
@Composable
fun MembershipBenefitSheet(benefit: MembershipBenefit?, onClose: () -> Unit) {
    val spacing = EonaTheme.spacing
    val shown = benefit != null
    if (shown) BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = shown, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
        // Dernière fiche gardée pendant la sortie.
        var last by remember { mutableStateOf(benefit) }
        if (benefit != null) last = benefit
        AnimatedVisibility(
            visible = shown,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            val current = last ?: return@AnimatedVisibility
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(EonaPlusStyle.canvas)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.xxl)
                    .padding(top = spacing.sm, bottom = spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(spacing.xxl),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(36.dp).height(5.dp).clip(RoundedCornerShape(50)).background(EonaPlusStyle.line.copy(alpha = 0.3f)))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        EonaText(
                            "Fermer",
                            style = EonaTheme.typography.bodyStrong,
                            color = current.color,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(role = Role.Button, onClick = onClose)
                                .padding(horizontal = spacing.sm, vertical = spacing.sm),
                        )
                    }
                }
                BenefitTile(current, 76.dp, 34.dp, 24.dp)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    EonaText(current.title, style = EonaTheme.typography.titleLarge, color = EonaPlusStyle.primary)
                    EonaText(current.detail, style = EonaTheme.typography.body, color = EonaPlusStyle.secondary)
                }
                EonaPlusGroup {
                    DetailValue("Gratuit", current.free, EonaPlusStyle.secondary)
                    EonaPlusDivider(Modifier.padding(horizontal = spacing.lg))
                    DetailValue("EONA+", current.plus, current.color)
                }
            }
        }
    }
}

@Composable
private fun DetailValue(title: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(EonaTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.lg),
    ) {
        EonaText(title, style = EonaTheme.typography.bodyStrong, color = EonaPlusStyle.secondary, modifier = Modifier.weight(1f))
        EonaText(value, style = EonaTheme.typography.bodyStrong, color = color)
    }
}

/** Deux formules indicatives, l'annuelle d'abord. Sélection partagée avec le bouton d'action. */
@Composable
fun SubscriptionPlans(selected: SubscriptionPlan, onSelect: (SubscriptionPlan) -> Unit) {
    EonaPlusGroup {
        PlanRow(SubscriptionPlan.YEARLY, selected.id == SubscriptionPlan.YEARLY.id) { onSelect(SubscriptionPlan.YEARLY) }
        EonaPlusDivider(Modifier.padding(start = 50.dp))
        PlanRow(SubscriptionPlan.MONTHLY, selected.id == SubscriptionPlan.MONTHLY.id) { onSelect(SubscriptionPlan.MONTHLY) }
    }
}

@Composable
private fun PlanRow(plan: SubscriptionPlan, selected: Boolean, onClick: () -> Unit) {
    val spacing = EonaTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = "${plan.title}, ${plan.priceLabel}${plan.periodLabel}. ${plan.perMonthLabel.orEmpty()} ${plan.savingLabel.orEmpty()}"
                stateDescription = if (selected) "Sélectionné" else "Non sélectionné"
            }
            .padding(spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        RadioMark(selected, Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    EonaText(plan.title, style = EonaTheme.typography.bodyStrong, color = EonaPlusStyle.primary)
                    plan.savingLabel?.let { EonaText(it, style = EonaTheme.typography.caption, color = EonaPlusStyle.mint, maxLines = 1) }
                }
                EonaText("${plan.priceLabel}${plan.periodLabel}", style = EonaTheme.typography.subhead, color = EonaPlusStyle.primary, maxLines = 1)
            }
            plan.perMonthLabel?.let { EonaText(it, style = EonaTheme.typography.footnote, color = EonaPlusStyle.secondary) }
        }
    }
}

/** Cercle de sélection : anneau lavande et point plein, ou anneau discret. */
@Composable
private fun RadioMark(selected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val stroke = 1.5.dp.toPx()
        val ring = if (selected) EonaPlusStyle.lavender else EonaPlusStyle.muted
        drawCircle(ring, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
        if (selected) drawCircle(EonaPlusStyle.lavender, radius = size.minDimension * 0.28f)
    }
}

/** Comparatif complet, replié au lancement. Les fonctions restent visibles dans le résumé. */
@Composable
fun MembershipComparison() {
    val spacing = EonaTheme.spacing
    var expanded by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "compare")
    EonaPlusGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Masquer le comparatif" else "Afficher le comparatif") { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Déplié" else "Replié" }
                .padding(spacing.lg)
                .heightIn(min = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EonaText("Comparer les offres", style = EonaTheme.typography.bodyStrong, color = EonaPlusStyle.primary, modifier = Modifier.weight(1f))
            EonaIcon(EonaIcons.ChevronDown, contentDescription = null, tint = EonaPlusStyle.lavender, size = 16.dp, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                FEATURES.forEach { (title, free, plus) ->
                    EonaPlusDivider(Modifier.padding(horizontal = spacing.lg))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { contentDescription = "$title. Gratuit : $free. EONA+ : $plus." }
                            .padding(spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        EonaText(title, style = EonaTheme.typography.bodyStrong, color = EonaPlusStyle.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xxl)) {
                            CompareValue("Gratuit", free, plus = false, modifier = Modifier.weight(1f))
                            CompareValue("EONA+", plus, plus = true, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareValue(title: String, value: String, plus: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.xs)) {
        EonaText(title, style = EonaTheme.typography.caption, color = if (plus) EonaPlusStyle.lavender else EonaPlusStyle.secondary)
        EonaText(value, style = EonaTheme.typography.subhead, color = if (plus) EonaPlusStyle.primary else EonaPlusStyle.secondary)
    }
}

private val FEATURES = listOf(
    Triple("Trajets", "4 / jour", "Illimités"),
    Triple("Trajets intelligents", "1 / jour", "Illimités"),
    Triple("Guidage, trafic, alertes", "Inclus", "Inclus"),
    Triple("Signalements", "Inclus", "Inclus"),
    Triple("Apple Music / autres", "Inclus", "Inclus"),
    Triple("EONA Taxi", "Verrouillé", "Inclus"),
    Triple("EONA Poids lourd", "Verrouillé", "Inclus"),
    Triple("Trajets en groupe", "Verrouillé", "Inclus"),
    Triple("Thème", "Cyan", "13 thèmes"),
    Triple("Feux en direct", "Verrouillé", "Inclus"),
)
