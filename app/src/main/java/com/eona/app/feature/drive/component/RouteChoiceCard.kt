package com.eona.app.feature.drive.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eona.app.core.model.FuelEstimate
import com.eona.app.core.model.Route
import com.eona.app.core.model.RouteChoice
import com.eona.app.core.model.RouteChoiceText
import com.eona.app.core.model.RouteOption
import com.eona.app.core.model.RoutePreference
import com.eona.app.core.model.expectedSeconds
import com.eona.app.designsystem.component.EonaButton
import com.eona.app.designsystem.component.EonaButtonVariant
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaSurface
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme

/**
 * Choix d'itinéraire, à la sélection d'une destination : Rapide, Éco, Perso (bientôt). Un panneau
 * au bas de la carte ; une option touchée est retenue, « Démarrer » lance le trajet. Chaque option
 * a ses états : calcul, prête, indisponible. Rapide : temps gagné, coût estimé, routes traversées ;
 * Éco : temps en plus, km et euros économisés. Copie de RouteChoiceCard.swift.
 */
@Composable
fun RouteChoiceCard(
    choice: RouteChoice,
    fuel: FuelEstimate?,
    onSelect: (RoutePreference) -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    stops: List<com.eona.app.core.model.Place> = emptyList(),
    canAddStop: Boolean = false,
    onAddStop: () -> Unit = {},
    onEditStops: () -> Unit = {},
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    EonaSurface(
        modifier = modifier.fillMaxWidth(),
        shape = EonaTheme.shapes.xxl,
        color = colors.surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = EonaTheme.elevation.level3,
    ) {
        Column(
            modifier = Modifier
                .padding(spacing.lg)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Header(choice, onClose, stops, canAddStop, onAddStop, onEditStops)
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                OptionRow(
                    kind = RoutePreference.Fastest,
                    option = choice.fastest,
                    subtitle = choice.fastest.route?.let { RouteChoiceText.fastest(it, choice.shortest.route, fuel) },
                    note = RouteChoiceText.roads(choice.fastest.route?.roads),
                    selected = choice.selected == RoutePreference.Fastest,
                ) { onSelect(RoutePreference.Fastest) }
                OptionRow(
                    kind = RoutePreference.Shortest,
                    option = choice.shortest,
                    subtitle = choice.shortest.route?.let { RouteChoiceText.eco(it, choice.fastest.route, fuel) },
                    note = null,
                    selected = choice.selected == RoutePreference.Shortest,
                ) { onSelect(RoutePreference.Shortest) }
                CustomRow()
            }
            if (choice.failed) {
                EonaText(
                    "Itinéraire indisponible — vérifie la connexion et réessaie.",
                    style = EonaTheme.typography.footnote,
                    color = colors.textSecondary,
                )
                EonaButton("Réessayer", onRetry, variant = EonaButtonVariant.Secondary, fillWidth = true)
            } else {
                EonaButton(
                    "Démarrer",
                    onStart,
                    leadingIcon = EonaIcons.Navigation,
                    loading = choice.chosenRoute == null,
                    fillWidth = true,
                )
            }
        }
    }
}

@Composable
private fun Header(
    choice: RouteChoice,
    onClose: () -> Unit,
    stops: List<com.eona.app.core.model.Place>,
    canAddStop: Boolean,
    onAddStop: () -> Unit,
    onEditStops: () -> Unit,
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EonaText("Choisis ton trajet", style = EonaTheme.typography.caption, color = colors.textTertiary)
            EonaText(
                choice.destination.name,
                style = EonaTheme.typography.headline,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (choice.destination.subtitle.isNotEmpty()) {
                EonaText(
                    choice.destination.subtitle,
                    style = EonaTheme.typography.footnote,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StopsRow(stops, canAddStop, onAddStop, onEditStops)
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(colors.surfaceHigh)
                .clickable(onClick = onClose)
                .semantics { contentDescription = "Fermer le choix de trajet" },
            contentAlignment = Alignment.Center,
        ) {
            EonaIcon(EonaIcons.Close, contentDescription = null, tint = colors.textSecondary, size = 16.dp)
        }
    }
}

/** « Via Boulangerie +2 » (liste des étapes) et « + Étape ». */
@Composable
private fun StopsRow(stops: List<com.eona.app.core.model.Place>, canAdd: Boolean, onAdd: () -> Unit, onEdit: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    if (stops.isEmpty() && !canAdd) return
    Row(Modifier.padding(top = spacing.xs), horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        if (stops.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.surfaceHigh.copy(alpha = 0.6f))
                    .clickable(onClick = onEdit)
                    .semantics { contentDescription = "Étapes : " + stops.joinToString(", ") { it.name } }
                    .padding(horizontal = spacing.sm, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                EonaText(RouteChoiceText.via(stops.map { it.name }), style = EonaTheme.typography.caption, color = colors.textPrimary, maxLines = 1)
                EonaIcon(EonaIcons.ChevronRight, contentDescription = null, tint = colors.textPrimary, size = 12.dp)
            }
        }
        if (canAdd) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.14f))
                    .clickable(onClick = onAdd)
                    .semantics { contentDescription = "Ajouter une étape" }
                    .padding(horizontal = spacing.sm, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                EonaIcon(EonaIcons.Plus, contentDescription = null, tint = colors.accent, size = 12.dp)
                EonaText("Étape", style = EonaTheme.typography.caption, color = colors.accent)
            }
        }
    }
}

/** Une option : icône, nom, ce qu'elle apporte, routes traversées, temps et distance. Retenue : contour accent. */
@Composable
private fun OptionRow(
    kind: RoutePreference,
    option: RouteOption,
    subtitle: String?,
    note: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val route = option.route
    val shape = EonaTheme.shapes.lg
    val detail = when (option) {
        RouteOption.Loading -> "Calcul du trajet…"
        RouteOption.Unavailable -> "Indisponible pour l'instant"
        is RouteOption.Ready -> subtitle.orEmpty()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (option == RouteOption.Unavailable) 0.5f else 1f)
            .clip(shape)
            .background(if (selected) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.accent else colors.border, shape)
            .clickable(
                enabled = route != null,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = accessibility(kind, route, detail, note) }
            .padding(horizontal = spacing.md, vertical = spacing.sm + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        OptionIcon(kind.icon, kind.tint())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EonaText(kind.title, style = EonaTheme.typography.headline, color = colors.textPrimary)
            EonaText(detail, style = EonaTheme.typography.footnote, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null && route != null) {
                EonaText(note, style = EonaTheme.typography.caption, color = colors.textTertiary, maxLines = 1)
            }
        }
        when (option) {
            RouteOption.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = colors.textTertiary,
                strokeWidth = 2.dp,
            )
            RouteOption.Unavailable -> Unit
            is RouteOption.Ready -> Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 56.dp)) {
                EonaText(
                    RouteChoiceText.duration(option.route.expectedSeconds),
                    style = EonaTheme.typography.title.copy(fontWeight = FontWeight.Bold),
                    color = if (selected) colors.accent else colors.textPrimary,
                    maxLines = 1,
                )
                EonaText(
                    "${RouteChoiceText.distance(option.route.distanceMeters)} · ${RouteChoiceText.arrival(option.route)}",
                    style = EonaTheme.typography.caption,
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** « Perso » : visible, grisée, pas encore disponible. */
@Composable
private fun CustomRow() {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val dash = colors.border
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.6f)
            .drawBehind {
                drawRoundRect(
                    color = dash,
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .semantics { contentDescription = "Perso, bientôt disponible" }
            .padding(horizontal = spacing.md, vertical = spacing.sm + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        OptionIcon(EonaIcons.Sliders, colors.textTertiary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EonaText("Perso", style = EonaTheme.typography.headline, color = colors.textSecondary)
            EonaText("Ton trajet, tes préférences", style = EonaTheme.typography.footnote, color = colors.textTertiary, maxLines = 1)
        }
        EonaText(
            "Bientôt",
            style = EonaTheme.typography.caption,
            color = colors.textSecondary,
            modifier = Modifier
                .clip(CircleShape)
                .background(colors.surfaceHigh.copy(alpha = 0.6f))
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
        )
    }
}

/** Icône d'option dans sa tuile teintée. */
@Composable
private fun OptionIcon(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(EonaTheme.shapes.md)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        EonaIcon(icon, contentDescription = null, tint = tint, size = 20.dp)
    }
}

private val RoutePreference.title: String
    get() = when (this) {
        RoutePreference.Fastest -> "Rapide"
        RoutePreference.Shortest -> "Éco"
    }

private val RoutePreference.icon: ImageVector
    get() = when (this) {
        RoutePreference.Fastest -> EonaIcons.Bolt
        RoutePreference.Shortest -> EonaIcons.Leaf
    }

@Composable
private fun RoutePreference.tint(): Color = when (this) {
    RoutePreference.Fastest -> EonaTheme.colors.accent
    RoutePreference.Shortest -> EonaTheme.colors.success
}

private fun accessibility(kind: RoutePreference, route: Route?, detail: String, note: String?): String {
    route ?: return "${kind.title}, $detail"
    return "${kind.title}, ${RouteChoiceText.duration(route.expectedSeconds)}, ${RouteChoiceText.distance(route.distanceMeters)}, " +
        "arrivée ${RouteChoiceText.arrival(route)}. $detail" + (note?.let { ". $it" } ?: "")
}
