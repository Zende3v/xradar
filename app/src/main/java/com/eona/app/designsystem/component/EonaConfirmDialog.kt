package com.eona.app.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eona.app.designsystem.theme.EonaTheme

/**
 * A question over the whole screen (place it last in a full-size box): the title, what the
 * action does, "Annuler" and the action itself, red when it cannot be undone.
 */
@Composable
fun EonaConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = true,
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        EonaSurface(
            modifier = Modifier.padding(spacing.xxl),
            shape = EonaTheme.shapes.xl,
            color = colors.surfaceElevated,
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier.padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                EonaText(title, style = EonaTheme.typography.headline, color = colors.textPrimary)
                EonaText(message, style = EonaTheme.typography.subhead, color = colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Box(
                        Modifier.weight(1f).clip(EonaTheme.shapes.lg).border(1.dp, colors.border, EonaTheme.shapes.lg)
                            .clickable(onClick = onCancel).padding(vertical = spacing.md),
                        contentAlignment = Alignment.Center,
                    ) { EonaText("Annuler", style = EonaTheme.typography.bodyStrong, color = colors.textPrimary) }
                    Box(
                        Modifier.weight(1f).clip(EonaTheme.shapes.lg)
                            .background(if (destructive) colors.danger else colors.accent)
                            .clickable(onClick = onConfirm).padding(vertical = spacing.md, horizontal = spacing.sm),
                        contentAlignment = Alignment.Center,
                    ) { EonaText(confirmLabel, style = EonaTheme.typography.bodyStrong, color = if (destructive) Color.White else colors.onAccent) }
                }
            }
        }
    }
}
