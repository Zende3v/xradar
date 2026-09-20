package com.eona.app.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme

/** Lightweight top bar: optional back button, title, optional trailing actions. */
@Composable
fun EonaTopBar(
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
                start = EonaTheme.spacing.sm,
                end = EonaTheme.spacing.md,
                top = EonaTheme.spacing.sm,
                bottom = EonaTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm),
    ) {
        if (onBack != null) {
            EonaIconButton(
                icon = EonaIcons.ArrowLeft,
                contentDescription = "Retour",
                onClick = onBack,
                tint = EonaTheme.colors.textPrimary,
            )
        }
        EonaText(
            text = title,
            style = EonaTheme.typography.title,
            color = EonaTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}
