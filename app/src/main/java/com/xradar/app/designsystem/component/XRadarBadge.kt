package com.xradar.app.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.xradar.app.designsystem.theme.XRadarTheme

/** Small status label: tinted text on a low-opacity fill of the same hue. */
@Composable
fun XRadarBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = XRadarTheme.colors.accent,
) {
    XRadarText(
        text = text.uppercase(),
        style = XRadarTheme.typography.caption,
        color = color,
        modifier = modifier
            .clip(XRadarTheme.shapes.pill)
            .background(color.copy(alpha = 0.14f))
            .padding(
                horizontal = XRadarTheme.spacing.sm,
                vertical = XRadarTheme.spacing.xs,
            ),
    )
}
