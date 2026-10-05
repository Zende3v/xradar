package com.eona.app.feature.drive.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.eona.app.core.model.Place
import com.eona.app.core.model.RouteChoiceText
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme

/**
 * Étapes du trajet (iOS StopsSheet) : ordre au glisser-déposer par la poignée, retrait d'un geste,
 * ajout en bas. Arrivée fixe, en dernier. Chaque changement recalcule l'itinéraire.
 */
@Composable
fun StopsSheetContent(
    stops: List<Place>,
    arrival: Place?,
    canAdd: Boolean,
    onChange: (List<Place>) -> Unit,
    onAdd: () -> Unit,
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        EonaText("Étapes", style = EonaTheme.typography.title, color = colors.textPrimary)
        if (stops.isEmpty()) {
            EonaText("Aucune étape. Ajoute une adresse sur le trajet.", style = EonaTheme.typography.footnote, color = colors.textSecondary)
        } else {
            ReorderableStops(stops, onChange)
            EonaText("Glisse la poignée pour changer l'ordre.", style = EonaTheme.typography.footnote, color = colors.textTertiary)
        }
        arrival?.let { ArrivalRow(it) }
        if (canAdd) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(EonaTheme.shapes.lg)
                    .background(colors.accent.copy(alpha = 0.14f))
                    .clickable(onClick = onAdd)
                    .padding(vertical = spacing.md),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EonaIcon(EonaIcons.Plus, contentDescription = null, tint = colors.accent, size = 18.dp)
                EonaText("Ajouter une étape", style = EonaTheme.typography.bodyStrong, color = colors.accent, modifier = Modifier.padding(start = spacing.sm))
            }
        }
    }
}

/** Les étapes, numérotées comme sur la carte ; la poignée déplace, la croix retire. */
@Composable
private fun ReorderableStops(stops: List<Place>, onChange: (List<Place>) -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    var order by remember(stops) { mutableStateOf(stops) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { STOP_ROW.toPx() }
    Column {
        order.forEachIndexed { index, place ->
            key(place.id) {
                val moving = dragged == place.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(STOP_ROW)
                        .zIndex(if (moving) 1f else 0f)
                        .graphicsLayer { translationY = if (moving) offset else 0f }
                        .clip(EonaTheme.shapes.md)
                        .background(if (moving) colors.surfaceHigh else Color.Transparent)
                        .semantics { contentDescription = "Étape ${index + 1}, ${place.name}" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    NumberDisc(index + 1)
                    PlaceLines(place, caption = null, modifier = Modifier.weight(1f))
                    RoundAction(EonaIcons.Close, "Retirer ${place.name}") { onChange(order.filter { it.id != place.id }) }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .pointerInput(place.id) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragged = place.id
                                        offset = 0f
                                    },
                                    onDragEnd = {
                                        dragged = null
                                        offset = 0f
                                        onChange(order)
                                    },
                                    onDragCancel = {
                                        dragged = null
                                        offset = 0f
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    offset += amount.y
                                    val i = order.indexOfFirst { it.id == place.id }
                                    if (offset > rowPx / 2 && i in 0 until order.lastIndex) {
                                        order = order.toMutableList().apply { add(i + 1, removeAt(i)) }
                                        offset -= rowPx
                                    } else if (offset < -rowPx / 2 && i > 0) {
                                        order = order.toMutableList().apply { add(i - 1, removeAt(i)) }
                                        offset += rowPx
                                    }
                                }
                            }
                            .semantics { contentDescription = "Déplacer ${place.name}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        EonaIcon(EonaIcons.Menu, contentDescription = null, tint = colors.textTertiary, size = 20.dp)
                    }
                }
            }
        }
    }
}

/** L'arrivée, sous les étapes : fixe. */
@Composable
private fun ArrivalRow(place: Place) {
    val colors = EonaTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Arrivée, ${place.name}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            EonaIcon(EonaIcons.Flag, contentDescription = null, tint = colors.accent, size = 14.dp)
        }
        PlaceLines(place, caption = "Arrivée", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NumberDisc(number: Int) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(EonaTheme.colors.accent),
        contentAlignment = Alignment.Center,
    ) {
        EonaText(
            "$number",
            style = EonaTheme.typography.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            color = EonaTheme.colors.onAccent,
        )
    }
}

@Composable
private fun PlaceLines(place: Place, caption: String?, modifier: Modifier = Modifier) {
    val colors = EonaTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        caption?.let { EonaText(it, style = EonaTheme.typography.caption, color = colors.textTertiary) }
        EonaText(place.name, style = EonaTheme.typography.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (place.subtitle.isNotBlank()) {
            EonaText(place.subtitle, style = EonaTheme.typography.footnote, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(EonaTheme.colors.surfaceHigh)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        EonaIcon(icon, contentDescription = null, tint = EonaTheme.colors.textSecondary, size = 14.dp)
    }
}

/** En route : « 2 étapes · Boulangerie » (la prochaine), ou « + Étape » sans étape. */
@Composable
fun StopsChip(stops: List<Place>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val label = if (stops.isEmpty()) "Étape" else RouteChoiceText.stops(stops.map { it.name })
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(colors.surface.copy(alpha = 0.62f))
            .border(1.dp, colors.border, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (stops.isEmpty()) "Ajouter une étape" else label }
            .padding(horizontal = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        EonaIcon(if (stops.isEmpty()) EonaIcons.Plus else EonaIcons.MapPin, contentDescription = null, tint = colors.accent, size = 14.dp)
        EonaText(
            label,
            style = EonaTheme.typography.caption,
            color = if (stops.isEmpty()) colors.textSecondary else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Un mot bref sous la barre du haut : étape atteinte. Touché : refermé. */
@Composable
fun HudNoticeBanner(icon: ImageVector, tint: Color, text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EonaTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(EonaTheme.shapes.lg)
            .background(colors.surface.copy(alpha = 0.92f))
            .border(1.dp, tint.copy(alpha = 0.6f), EonaTheme.shapes.lg)
            .clickable(onClick = onDismiss)
            .padding(EonaTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm),
    ) {
        EonaIcon(icon, contentDescription = null, tint = tint, size = 20.dp)
        EonaText(text, style = EonaTheme.typography.subhead, color = colors.textPrimary, modifier = Modifier.weight(1f))
    }
}

private val STOP_ROW = 60.dp
