package com.xradar.app.feature.drive.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xradar.app.core.model.TripInfo
import com.xradar.app.designsystem.component.XRadarSurface
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.theme.XRadarTheme

/** Frosted top strip: time remaining, distance and arrival time. */
@Composable
fun TripStrip(trip: TripInfo, modifier: Modifier = Modifier) {
    val colors = XRadarTheme.colors
    XRadarSurface(
        modifier = modifier,
        shape = XRadarTheme.shapes.lg,
        color = colors.surface.copy(alpha = 0.62f),
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = XRadarTheme.elevation.level2,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = XRadarTheme.spacing.lg,
                vertical = XRadarTheme.spacing.md,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.lg),
        ) {
            Cluster(trip.remainingLabel, "Restantes", colors.accent, Modifier.weight(1f))
            VDivider()
            Cluster(trip.distanceLabel, "Distance", colors.textPrimary, Modifier.weight(1f))
            VDivider()
            Cluster(trip.arrivalLabel, "Arrivée", colors.textPrimary, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Cluster(value: String, label: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        XRadarText(value, style = XRadarTheme.typography.numeric, color = valueColor)
        XRadarText(label.uppercase(), style = XRadarTheme.typography.caption, color = XRadarTheme.colors.textTertiary)
    }
}

@Composable
private fun VDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(28.dp)
            .background(XRadarTheme.colors.separator),
    )
}
