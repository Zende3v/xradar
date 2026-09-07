package com.xradar.app.feature.drive.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xradar.app.core.model.RoadAlert
import com.xradar.app.designsystem.component.XRadarCard
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.theme.XRadarTheme

/** Bottom-sheet card describing the active [RoadAlert]. */
@Composable
fun AlertSheet(alert: RoadAlert, modifier: Modifier = Modifier) {
    val colors = XRadarTheme.colors
    val accent = alert.type.color()

    XRadarCard(
        modifier = modifier.fillMaxWidth(),
        shape = XRadarTheme.shapes.xxl,
        color = colors.surfaceElevated,
        shadowElevation = XRadarTheme.elevation.level4,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(XRadarTheme.shapes.md)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                XRadarIcon(alert.type.icon(), contentDescription = null, tint = accent, size = 24.dp)
            }

            Column(modifier = Modifier.weight(1f)) {
                XRadarText(alert.title, style = XRadarTheme.typography.headline, color = colors.textPrimary)
                val subtitle = buildString {
                    alert.roadLabel?.let { append(it) }
                    alert.speedLimitKmh?.let {
                        if (isNotEmpty()) append(" · ")
                        append("limité à $it km/h")
                    }
                }
                if (subtitle.isNotEmpty()) {
                    XRadarText(subtitle, style = XRadarTheme.typography.subhead, color = colors.textSecondary)
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                XRadarText(
                    text = formatDistance(alert.distanceMeters),
                    style = XRadarTheme.typography.title.copy(fontWeight = FontWeight.Bold),
                    color = accent,
                )
                XRadarText("dans ${alert.etaSeconds} s", style = XRadarTheme.typography.caption, color = colors.textTertiary)
            }
        }

        Row(
            modifier = Modifier.padding(top = XRadarTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.md),
        ) {
            XRadarText("Fiabilité", style = XRadarTheme.typography.caption, color = colors.textTertiary)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(XRadarTheme.shapes.pill)
                    .background(colors.surfaceHigh),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(alert.confidence)
                        .height(6.dp)
                        .clip(XRadarTheme.shapes.pill)
                        .background(colors.accent),
                )
            }
            XRadarText("${(alert.confidence * 100).toInt()} %", style = XRadarTheme.typography.caption, color = colors.textSecondary)
        }

        alert.lastReportedLabel?.let { label ->
            XRadarText(
                text = "Dernier signalement · ${label.removePrefix("signalé ")}",
                style = XRadarTheme.typography.footnote,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = XRadarTheme.spacing.sm),
            )
        }
    }
}

private fun formatDistance(meters: Int): String =
    if (meters >= 1000) "%.1f km".format(meters / 1000f).replace('.', ',') else "$meters m"
