package com.eona.app.feature.drive.component

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
import com.eona.app.core.model.TripInfo
import com.eona.app.designsystem.component.EonaSurface
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.theme.EonaTheme

/** Frosted top strip: time remaining, distance and arrival time. */
@Composable
fun TripStrip(trip: TripInfo, modifier: Modifier = Modifier) {
    val colors = EonaTheme.colors
    EonaSurface(
        modifier = modifier,
        shape = EonaTheme.shapes.lg,
        color = colors.surface.copy(alpha = 0.62f),
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = EonaTheme.elevation.level2,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = EonaTheme.spacing.lg,
                vertical = EonaTheme.spacing.md,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.lg),
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
        EonaText(value, style = EonaTheme.typography.numeric, color = valueColor)
        EonaText(label.uppercase(), style = EonaTheme.typography.caption, color = EonaTheme.colors.textTertiary)
    }
}

@Composable
private fun VDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(28.dp)
            .background(EonaTheme.colors.separator),
    )
}
