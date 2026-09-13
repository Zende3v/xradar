package com.xradar.app.feature.drive.component

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
import com.xradar.app.core.geo.GuidanceText
import com.xradar.app.core.model.GuidanceInstruction
import com.xradar.app.core.model.Maneuver
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarSurface
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme

/** Top-of-HUD turn card: maneuver arrow + distance + the road you turn onto. */
@Composable
fun GuidanceBanner(instruction: GuidanceInstruction, modifier: Modifier = Modifier) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing

    XRadarSurface(
        modifier = modifier,
        shape = XRadarTheme.shapes.lg,
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
                XRadarIcon(
                    maneuverIcon(instruction.maneuver),
                    contentDescription = null,
                    tint = colors.accent,
                    size = 34.dp,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                XRadarText(
                    GuidanceText.distanceLabel(instruction.distanceMeters),
                    style = XRadarTheme.typography.titleLarge,
                    color = colors.textPrimary,
                )
                XRadarText(
                    instruction.roadName ?: instruction.primaryText,
                    style = XRadarTheme.typography.subhead,
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
    Maneuver.Depart -> XRadarIcons.Navigation
    Maneuver.Straight -> XRadarIcons.ManeuverStraight
    Maneuver.SlightLeft -> XRadarIcons.ManeuverSlightLeft
    Maneuver.SlightRight -> XRadarIcons.ManeuverSlightRight
    Maneuver.Left -> XRadarIcons.ManeuverLeft
    Maneuver.Right -> XRadarIcons.ManeuverRight
    Maneuver.SharpLeft -> XRadarIcons.ManeuverSharpLeft
    Maneuver.SharpRight -> XRadarIcons.ManeuverSharpRight
    Maneuver.Uturn -> XRadarIcons.ManeuverUturn
    Maneuver.Roundabout -> XRadarIcons.ManeuverRoundabout
    Maneuver.Merge -> XRadarIcons.ManeuverMerge
    Maneuver.Ramp -> XRadarIcons.ManeuverSlightRight
    Maneuver.ForkLeft -> XRadarIcons.ManeuverSlightLeft
    Maneuver.ForkRight -> XRadarIcons.ManeuverSlightRight
    Maneuver.Arrive -> XRadarIcons.Flag
}

@Preview(name = "Guidage · dark", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380)
@Composable
private fun GuidanceBannerPreview() {
    XRadarTheme(darkTheme = true) {
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
