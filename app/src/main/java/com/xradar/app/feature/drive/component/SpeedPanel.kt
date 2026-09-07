package com.xradar.app.feature.drive.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xradar.app.core.model.SpeedStatus
import com.xradar.app.designsystem.component.XRadarSurface
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.theme.XRadarTheme

/**
 * Combined speed + limit widget: current speed on the left, the circular limit
 * sign on the right, in one frosted card. The sign only shows when a limit is
 * known (near a speed radar for now).
 */
@Composable
fun SpeedPanel(
    speedKmh: Int,
    status: SpeedStatus?,
    searching: Boolean,
    limitKmh: Int?,
    modifier: Modifier = Modifier,
) {
    val colors = XRadarTheme.colors
    val targetColor = when {
        searching -> colors.textSecondary
        status == SpeedStatus.Over -> colors.speedOver
        status == SpeedStatus.Caution -> colors.warning
        status == SpeedStatus.Safe -> colors.speedSafe
        else -> colors.textPrimary
    }
    val speedColor by animateColorAsState(targetColor, label = "speedColor")
    val animatedSpeed by animateIntAsState(targetValue = speedKmh, label = "speedValue")

    XRadarSurface(
        modifier = modifier,
        shape = XRadarTheme.shapes.xl,
        color = colors.surface.copy(alpha = 0.62f),
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = XRadarTheme.elevation.level3,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = XRadarTheme.spacing.lg,
                vertical = XRadarTheme.spacing.md,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.lg),
        ) {
            Column {
                XRadarText(
                    text = if (searching) "--" else animatedSpeed.toString(),
                    style = XRadarTheme.typography.displayHero.copy(
                        fontSize = 52.sp,
                        lineHeight = 52.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = speedColor,
                )
                XRadarText(
                    text = if (searching) "Recherche GPS…" else "km/h",
                    style = XRadarTheme.typography.caption,
                    color = colors.textTertiary,
                )
            }
            if (limitKmh != null) {
                SpeedLimitSign(limitKmh = limitKmh, size = 60.dp)
            }
        }
    }
}
