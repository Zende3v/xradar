package com.eona.app.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eona.app.data.preferences.AccentColor
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.subscription.EonaPlusLabel
import com.eona.app.feature.subscription.EonaPlusStyle

/** Pastille d'un thème : la couleur, ou le dégradé de la palette. */
@Composable
fun ThemeSwatch(colour: AccentColor, size: Dp, modifier: Modifier = Modifier) {
    val values = colour.paletteValues.map { Color(0xFF000000.toInt() or it) }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (values.size > 1) Brush.linearGradient(values) else Brush.linearGradient(listOf(values[0], values[0]))),
    )
}

/** « Thème » : une ligne compacte ; les quinze choix dans la feuille dédiée (iOS accentPicker). */
@Composable
fun ThemeRow(selection: AccentColor, hasPlus: Boolean, onOpen: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClickLabel = "Choisir une couleur ou une palette", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "Thème, ${selection.label}" }
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        EonaText("Thème", style = EonaTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
        ThemeSwatch(selection, 22.dp)
        EonaText(selection.label, style = EonaTheme.typography.callout, color = colors.textSecondary)
        if (selection.requiresPlus && !hasPlus) EonaIcon(EonaIcons.Lock, contentDescription = null, tint = colors.textTertiary, size = 14.dp)
        EonaIcon(EonaIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, size = 16.dp)
    }
}

/**
 * Feuille « Thème » (iOS ThemePickerSheet) : onze couleurs, quatre palettes. Fond OLED. Choix
 * appliqué tout de suite ; gratuit : [onPick] ouvre les offres. À placer en dernier.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemePickerSheet(open: Boolean, selection: AccentColor, hasPlus: Boolean, onPick: (AccentColor) -> Unit, onClose: () -> Unit) {
    val spacing = EonaTheme.spacing
    val haptic = LocalHapticFeedback.current
    if (open) BackHandler(onBack = onClose)
    val pick: (AccentColor) -> Unit = { colour ->
        if (colour != selection) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onPick(colour)
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
        AnimatedVisibility(
            visible = open,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(EonaPlusStyle.canvas)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.xl)
                    .padding(top = spacing.sm, bottom = spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                // Poignée, titre, « Fermer ».
                Box(Modifier.fillMaxWidth().height(44.dp)) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .width(36.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.3f)),
                    )
                    EonaText("Thème", style = EonaTheme.typography.headline, color = EonaPlusStyle.primary, modifier = Modifier.align(Alignment.Center))
                    EonaText(
                        "Fermer",
                        style = EonaTheme.typography.bodyStrong,
                        color = EonaPlusStyle.secondary,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .clip(RoundedCornerShape(50))
                            .clickable(role = Role.Button, onClick = onClose)
                            .padding(horizontal = spacing.sm, vertical = spacing.sm),
                    )
                }
                Row(
                    modifier = Modifier.padding(vertical = spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    ThemeSwatch(selection, 32.dp)
                    EonaText(selection.label, style = EonaTheme.typography.bodyStrong, color = EonaPlusStyle.primary, modifier = Modifier.weight(1f))
                    if (selection.requiresPlus && !hasPlus) EonaIcon(EonaIcons.Lock, contentDescription = "Thèmes EONA+", tint = EonaPlusStyle.muted, size = 15.dp)
                }
                EonaPlusLabel("Couleurs", color = EonaPlusStyle.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    AccentColor.unicolours.forEach { colour ->
                        val chosen = colour == selection
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .selectable(selected = chosen, role = Role.RadioButton) { pick(colour) }
                                .semantics {
                                    contentDescription = colour.label
                                    stateDescription = if (chosen) "Sélectionné" else ""
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .border(1.5.dp, if (chosen) EonaPlusStyle.primary else Color.Transparent, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { ThemeSwatch(colour, 28.dp) }
                        }
                    }
                }
                Spacer(Modifier.height(spacing.md))
                EonaPlusLabel("Palettes", color = EonaPlusStyle.muted)
                AccentColor.palettes.forEach { palette ->
                    val chosen = palette == selection
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clip(EonaTheme.shapes.md)
                            .selectable(selected = chosen, role = Role.RadioButton) { pick(palette) }
                            .padding(vertical = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        ThemeSwatch(palette, 28.dp)
                        EonaText(palette.label, style = EonaTheme.typography.callout, color = EonaPlusStyle.primary, modifier = Modifier.weight(1f))
                        when {
                            palette.requiresPlus && !hasPlus -> EonaIcon(EonaIcons.Lock, contentDescription = null, tint = EonaPlusStyle.muted, size = 15.dp)
                            chosen -> EonaIcon(EonaIcons.Check, contentDescription = null, tint = Color(0xFF000000.toInt() or palette.rgb), size = 18.dp)
                        }
                    }
                }
            }
        }
    }
}
