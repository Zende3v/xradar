package com.eona.app.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme

/** Bespoke search input (BasicTextField, no Material chrome) with clear button. */
@Composable
fun EonaSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Rechercher une destination",
    autoFocus: Boolean = false,
) {
    val colors = EonaTheme.colors
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }

    Row(
        modifier = modifier
            .clip(EonaTheme.shapes.md)
            // Same tint family as the HUD surfaces: an elevated (lighter) fill here
            // reads as a grey bar cutting through the bar.
            .background(colors.surface.copy(alpha = 0.62f))
            .border(BorderStroke(1.dp, colors.border), EonaTheme.shapes.md)
            .padding(horizontal = EonaTheme.spacing.md, vertical = EonaTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm),
    ) {
        EonaIcon(EonaIcons.Search, contentDescription = null, tint = colors.textTertiary, size = 20.dp)
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = EonaTheme.typography.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (autoFocus) Modifier.focusRequester(focusRequester) else Modifier),
            )
            if (value.isEmpty()) {
                EonaText(placeholder, style = EonaTheme.typography.body, color = colors.textTertiary)
            }
        }
        if (value.isNotEmpty()) {
            EonaIconButton(
                icon = EonaIcons.Close,
                contentDescription = "Effacer",
                onClick = { onValueChange("") },
                tint = colors.textTertiary,
                size = 28.dp,
            )
        }
    }
}
