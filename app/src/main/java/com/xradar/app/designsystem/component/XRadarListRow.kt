package com.xradar.app.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xradar.app.designsystem.theme.XRadarTheme

/**
 * Settings/list row: optional colored leading icon tile, a title with optional
 * subtitle, and a trailing slot (a switch, a value, a chevron…). Tappable when
 * [onClick] is set (subtle pressed tint).
 */
@Composable
fun XRadarListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leadingTint: Color = XRadarTheme.colors.textSecondary,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        if (onClick != null && pressed) colors.surfaceHigh.copy(alpha = 0.5f) else Color.Transparent,
        label = "rowBackground",
    )

    val clickModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier)
            .background(background)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (leadingIcon != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(XRadarTheme.shapes.sm)
                    .background(leadingTint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                XRadarIcon(leadingIcon, contentDescription = null, tint = leadingTint, size = 18.dp)
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            XRadarText(title, style = XRadarTheme.typography.body, color = colors.textPrimary)
            if (subtitle != null) {
                XRadarText(subtitle, style = XRadarTheme.typography.footnote, color = colors.textTertiary)
            }
        }

        if (trailing != null) {
            trailing()
        }
    }
}
