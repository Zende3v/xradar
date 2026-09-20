package com.eona.app.feature.drive.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.eona.app.core.geo.GuidanceText
import com.eona.app.core.model.GuidanceInstruction
import com.eona.app.core.model.Maneuver
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaSurface
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme

/** Top-of-HUD turn card: maneuver arrow + distance + the road you turn onto. */
@Composable
fun GuidanceBanner(instruction: GuidanceInstruction, modifier: Modifier = Modifier) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing

    EonaSurface(
        modifier = modifier,
        shape = EonaTheme.shapes.lg,
        color = colors.surface.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, colors.border),
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(colors.accent.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                EonaIcon(
                    maneuverIcon(instruction.maneuver),
                    contentDescription = null,
                    tint = colors.accent,
                    size = 34.dp,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                EonaText(
                    GuidanceText.distanceLabel(instruction.distanceMeters),
                    style = EonaTheme.typography.titleLarge,
                    color = colors.textPrimary,
                )
                EonaText(
                    instruction.roadName ?: instruction.primaryText,
                    style = EonaTheme.typography.subhead,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Arrow for a maneuver, shared by the top banner and the E3 dock. */
internal fun maneuverIcon(maneuver: Maneuver): ImageVector = when (maneuver) {
    Maneuver.Depart -> EonaIcons.Navigation
    Maneuver.Straight -> EonaIcons.ManeuverStraight
    Maneuver.SlightLeft -> EonaIcons.ManeuverSlightLeft
    Maneuver.SlightRight -> EonaIcons.ManeuverSlightRight
    Maneuver.Left -> EonaIcons.ManeuverLeft
    Maneuver.Right -> EonaIcons.ManeuverRight
    Maneuver.SharpLeft -> EonaIcons.ManeuverSharpLeft
    Maneuver.SharpRight -> EonaIcons.ManeuverSharpRight
    Maneuver.Uturn -> EonaIcons.ManeuverUturn
    Maneuver.Roundabout -> EonaIcons.ManeuverRoundabout
    Maneuver.Merge -> EonaIcons.ManeuverMerge
    Maneuver.Ramp -> EonaIcons.ManeuverSlightRight
    Maneuver.ForkLeft -> EonaIcons.ManeuverSlightLeft
    Maneuver.ForkRight -> EonaIcons.ManeuverSlightRight
    Maneuver.Arrive -> EonaIcons.Flag
}

@Preview(name = "Guidage · dark", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380)
@Composable
private fun GuidanceBannerPreview() {
    EonaTheme(darkTheme = true) {
        GuidanceBanner(
            instruction = GuidanceInstruction(
                maneuver = Maneuver.Right,
                distanceMeters = 250,
                primaryText = "Tournez à droite",
                roadName = "Rue de la République",
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}
