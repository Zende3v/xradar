package com.xradar.app.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme

/** Lightweight top bar: optional back button, title, optional trailing actions. */
@Composable
fun XRadarTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                start = XRadarTheme.spacing.sm,
                end = XRadarTheme.spacing.md,
                top = XRadarTheme.spacing.sm,
                bottom = XRadarTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.sm),
    ) {
        if (onBack != null) {
            XRadarIconButton(
                icon = XRadarIcons.ArrowLeft,
                contentDescription = "Retour",
                onClick = onBack,
                tint = XRadarTheme.colors.textPrimary,
            )
        }
        XRadarText(
            text = title,
            style = XRadarTheme.typography.title,
            color = XRadarTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}
