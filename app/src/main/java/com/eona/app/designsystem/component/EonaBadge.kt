package com.eona.app.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import com.eona.app.designsystem.theme.EonaTheme

/**
 * Small status label: tinted text on a low-opacity fill of the same hue, or, with [glow], white
 * text glowing on the dark tile of the Menu's icons.
 */
@Composable
fun EonaBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = EonaTheme.colors.accent,
    glow: Boolean = false,
) {
    val colors = EonaTheme.colors
    val tint = if (glow) colors.glowIcon else color
    val style = EonaTheme.typography.caption.let {
        if (glow) it.copy(shadow = Shadow(color = tint.copy(alpha = 0.6f), blurRadius = 8f)) else it
    }
    EonaText(
        text = text.uppercase(),
        style = style,
        color = tint,
        modifier = modifier
            .clip(EonaTheme.shapes.pill)
            .background(if (glow) colors.glowTile else color.copy(alpha = 0.14f))
            .padding(
                horizontal = EonaTheme.spacing.sm,
                vertical = EonaTheme.spacing.xs,
            ),
    )
}
