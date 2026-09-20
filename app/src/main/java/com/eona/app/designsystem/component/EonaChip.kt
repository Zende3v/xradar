package com.eona.app.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eona.app.designsystem.theme.EonaTheme

/**
 * Pill chip: optional leading status [dotColor], a label, and optional [trailing]
 * meta (distance, time). [selected] tints it with the accent; pass [onClick] to
 * make it toggle/filter.
 */
@Composable
fun EonaChip(
    label: String,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    trailing: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val shape = EonaTheme.shapes.pill

    val background = if (selected) colors.accent.copy(alpha = 0.16f) else colors.surfaceElevated
    val borderColor = if (selected) colors.accent.copy(alpha = 0.5f) else colors.border

    val interaction = remember { MutableInteractionSource() }
    val clickable = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(BorderStroke(1.dp, borderColor), shape)
            .then(clickable)
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (dotColor != null) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(shape)
                    .background(dotColor),
            )
        }
        EonaText(
            label,
            style = EonaTheme.typography.subhead,
            color = if (selected) colors.accent else colors.textPrimary,
        )
        if (trailing != null) {
            EonaText(trailing, style = EonaTheme.typography.caption, color = colors.textTertiary)
        }
    }
}
