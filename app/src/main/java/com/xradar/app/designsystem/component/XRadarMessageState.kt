package com.xradar.app.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xradar.app.designsystem.theme.XRadarTheme

/**
 * Centered full-area state: icon in a tinted disc, title, message, and up to two
 * actions. The single template for empty / error / permission / onboarding states.
 */
@Composable
fun XRadarMessageState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    iconTint: Color = XRadarTheme.colors.textTertiary,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing

    Box(
        modifier = modifier.fillMaxSize().padding(spacing.xxxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(XRadarTheme.shapes.xxl)
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                XRadarIcon(icon, contentDescription = null, tint = iconTint, size = 40.dp)
            }

            XRadarText(
                text = title,
                style = XRadarTheme.typography.title,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            XRadarText(
                text = message,
                style = XRadarTheme.typography.callout,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            if (primaryLabel != null && onPrimary != null) {
                Spacer(Modifier.height(spacing.sm))
                XRadarButton(primaryLabel, onClick = onPrimary, fillWidth = true)
            }
            if (secondaryLabel != null && onSecondary != null) {
                XRadarButton(
                    secondaryLabel,
                    onClick = onSecondary,
                    variant = XRadarButtonVariant.Ghost,
                    fillWidth = true,
                )
            }
        }
    }
}
