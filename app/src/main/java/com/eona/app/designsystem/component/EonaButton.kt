package com.eona.app.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.eona.app.designsystem.theme.EonaTheme

enum class EonaButtonVariant { Primary, Secondary, Ghost, Destructive }

/**
 * The one button. Variants carry intent; states (pressed / disabled / loading)
 * are built in. Press feedback is a subtle scale + container shift — no Material
 * ripple, for a calmer, more native feel.
 */
@Composable
fun EonaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: EonaButtonVariant = EonaButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    fillWidth: Boolean = false,
) {
    val colors = EonaTheme.colors
    val shape = EonaTheme.shapes.lg
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = enabled && !loading

    val scale by animateFloatAsState(if (pressed && active) 0.97f else 1f, label = "buttonScale")

    val enabledContainer: Color
    val enabledContent: Color
    val pressedContainer: Color
    val baseBorder: BorderStroke?
    when (variant) {
        EonaButtonVariant.Primary -> {
            enabledContainer = colors.accent
            enabledContent = colors.onAccent
            pressedContainer = colors.accentPressed
            baseBorder = null
        }
        EonaButtonVariant.Secondary -> {
            enabledContainer = colors.surfaceElevated
            enabledContent = colors.textPrimary
            pressedContainer = colors.surfaceHigh
            baseBorder = BorderStroke(1.dp, colors.border)
        }
        EonaButtonVariant.Ghost -> {
            enabledContainer = Color.Transparent
            enabledContent = colors.accent
            pressedContainer = colors.surface
            baseBorder = null
        }
        EonaButtonVariant.Destructive -> {
            enabledContainer = colors.danger
            enabledContent = Color.White
            pressedContainer = lerp(colors.danger, Color.Black, 0.15f)
            baseBorder = null
        }
    }

    val targetContainer = when {
        !enabled -> colors.surfaceElevated
        pressed -> pressedContainer
        else -> enabledContainer
    }
    val contentColor = if (enabled) enabledContent else colors.textDisabled
    val effectiveBorder = if (enabled) baseBorder else null
    val container by animateColorAsState(targetContainer, label = "buttonContainer")

    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(container)
            .then(if (effectiveBorder != null) Modifier.border(effectiveBorder, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = active, onClick = onClick)
            .height(52.dp)
            .padding(horizontal = EonaTheme.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = contentColor,
                strokeWidth = 2.dp,
            )
        } else {
            if (leadingIcon != null) {
                EonaIcon(leadingIcon, contentDescription = null, tint = contentColor, size = 20.dp)
            }
            EonaText(text, style = EonaTheme.typography.label, color = contentColor)
        }
    }
}
