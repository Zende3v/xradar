package com.xradar.app.designsystem.component

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
import com.xradar.app.designsystem.theme.XRadarTheme

/**
 * A question over the whole screen (place it last in a full-size box): the title, what the
 * action does, "Annuler" and the action itself, red when it cannot be undone.
 */
@Composable
fun XRadarConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = true,
) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        XRadarSurface(
            modifier = Modifier.padding(spacing.xxl),
            shape = XRadarTheme.shapes.xl,
            color = colors.surfaceElevated,
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier.padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                XRadarText(title, style = XRadarTheme.typography.headline, color = colors.textPrimary)
                XRadarText(message, style = XRadarTheme.typography.subhead, color = colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Box(
                        Modifier.weight(1f).clip(XRadarTheme.shapes.lg).border(1.dp, colors.border, XRadarTheme.shapes.lg)
                            .clickable(onClick = onCancel).padding(vertical = spacing.md),
                        contentAlignment = Alignment.Center,
                    ) { XRadarText("Annuler", style = XRadarTheme.typography.bodyStrong, color = colors.textPrimary) }
                    Box(
                        Modifier.weight(1f).clip(XRadarTheme.shapes.lg)
                            .background(if (destructive) colors.danger else colors.accent)
                            .clickable(onClick = onConfirm).padding(vertical = spacing.md, horizontal = spacing.sm),
                        contentAlignment = Alignment.Center,
                    ) { XRadarText(confirmLabel, style = XRadarTheme.typography.bodyStrong, color = if (destructive) Color.White else colors.onAccent) }
                }
            }
        }
    }
}
