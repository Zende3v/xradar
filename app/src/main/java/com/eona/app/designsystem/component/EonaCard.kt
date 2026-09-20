package com.eona.app.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eona.app.designsystem.theme.EonaTheme

/**
 * Elevated content container. Pass [onClick] to make it interactive — it then
 * gets a subtle pressed tint. Content is laid out in a [ColumnScope] with
 * [contentPadding].
 */
@Composable
fun EonaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = EonaTheme.shapes.xl,
    color: Color = EonaTheme.colors.surfaceElevated,
    border: BorderStroke? = BorderStroke(1.dp, EonaTheme.colors.border),
    shadowElevation: Dp = EonaTheme.elevation.level2,
    contentPadding: PaddingValues = PaddingValues(EonaTheme.spacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        if (onClick != null && pressed) lerp(color, Color.White, 0.04f) else color,
        label = "cardBackground",
    )

    val clickModifier = if (onClick != null) {
        modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        modifier
    }

    EonaSurface(
        modifier = clickModifier,
        shape = shape,
        color = background,
        border = border,
        shadowElevation = shadowElevation,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}
