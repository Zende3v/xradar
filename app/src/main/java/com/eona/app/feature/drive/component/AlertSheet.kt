package com.eona.app.feature.drive.component

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
import com.eona.app.core.model.RoadAlert
import com.eona.app.designsystem.component.EonaCard
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.theme.EonaTheme

/** Bottom-sheet card describing the active [RoadAlert]. */
@Composable
fun AlertSheet(alert: RoadAlert, modifier: Modifier = Modifier) {
    EonaCard(
        modifier = modifier.fillMaxWidth(),
        shape = EonaTheme.shapes.xxl,
        color = EonaTheme.colors.surfaceElevated,
        shadowElevation = EonaTheme.elevation.level4,
    ) {
        AlertDetail(alert)
    }
}

/** One alert in full: what and where, how far and how soon, how reliable, how fresh. */
@Composable
internal fun AlertDetail(alert: RoadAlert) {
    val colors = EonaTheme.colors
    val accent = alert.type.color()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(EonaTheme.shapes.md)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            EonaIcon(alert.type.icon(), contentDescription = null, tint = accent, size = 24.dp)
        }

        Column(modifier = Modifier.weight(1f)) {
            EonaText(alert.title, style = EonaTheme.typography.headline, color = colors.textPrimary)
            val subtitle = alertSubtitle(alert)
            if (subtitle.isNotEmpty()) {
                EonaText(subtitle, style = EonaTheme.typography.subhead, color = colors.textSecondary)
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            EonaText(
                text = formatDistance(alert.distanceMeters),
                style = EonaTheme.typography.title.copy(fontWeight = FontWeight.Bold),
                color = accent,
            )
            EonaText("dans ${alert.etaSeconds} s", style = EonaTheme.typography.caption, color = colors.textTertiary)
        }
    }

    Row(
        modifier = Modifier.padding(top = EonaTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.md),
    ) {
        EonaText("Fiabilité", style = EonaTheme.typography.caption, color = colors.textTertiary)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(EonaTheme.shapes.pill)
                .background(colors.surfaceHigh),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(alert.confidence)
                    .height(6.dp)
                    .clip(EonaTheme.shapes.pill)
                    .background(colors.accent),
            )
        }
        EonaText("${(alert.confidence * 100).toInt()} %", style = EonaTheme.typography.caption, color = colors.textSecondary)
    }

    alert.lastReportedLabel?.let { label ->
        EonaText(
            text = "Dernier signalement · ${label.removePrefix("signalé ")}",
            style = EonaTheme.typography.footnote,
            color = colors.textTertiary,
            modifier = Modifier.padding(top = EonaTheme.spacing.sm),
        )
    }
}

/** "Sens opposé · limité à 90 km/h" — empty when there is nothing to add. */
internal fun alertSubtitle(alert: RoadAlert): String = buildString {
    alert.roadLabel?.let { append(it) }
    alert.speedLimitKmh?.let {
        if (isNotEmpty()) append(" · ")
        append("limité à $it km/h")
    }
}

internal fun formatDistance(meters: Int): String =
    if (meters >= 1000) "%.1f km".format(meters / 1000f).replace('.', ',') else "$meters m"
