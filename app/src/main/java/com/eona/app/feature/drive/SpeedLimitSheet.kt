package com.eona.app.feature.drive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eona.app.core.model.SpeedLimits
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.drive.component.SpeedLimitSign
import com.eona.app.feature.drive.component.UnknownLimitSign

/**
 * "Nouvelle limitation", opened from the limit sign of the dock. It lives in the same sheet
 * as the report picker, but it is not a road event: it is sign maintenance, and the limit
 * only changes for everyone once other drivers agree (backend speedlimits store). The pick
 * asks for a confirmation that sends itself after a few seconds, like a report's direction.
 */
@Composable
fun SpeedLimitSheet(currentKmh: Int?, onReport: (Int) -> Unit, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf<Int?>(null) }
    DriveSheet(onDismiss = onDismiss) {
        val value = picked
        if (value == null) {
            LimitPicker(currentKmh = currentKmh, onPick = { picked = it })
        } else {
            LimitConfirm(currentKmh = currentKmh, newKmh = value, onBack = { picked = null }, onSend = onReport)
        }
    }
}

/** What the HUD shows now, then every limit the map knows, three per row. */
@Composable
private fun LimitPicker(currentKmh: Int?, onPick: (Int) -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    EonaText("Nouvelle limitation", style = EonaTheme.typography.title, color = colors.textPrimary)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
        CurrentSign(kmh = currentKmh, size = 44.dp)
        Column(modifier = Modifier.weight(1f)) {
            EonaText(
                if (currentKmh != null) "Limitation affichée : $currentKmh km/h" else "Aucune limitation connue ici",
                style = EonaTheme.typography.subhead,
                color = colors.textPrimary,
            )
            EonaText(
                "Choisis celle du panneau que tu vois.",
                style = EonaTheme.typography.footnote,
                color = colors.textSecondary,
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SpeedLimits.VALUES.chunked(PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                repeat(PER_ROW) { i ->
                    val kmh = row.getOrNull(i)
                    if (kmh == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        LimitTile(
                            kmh = kmh,
                            isCurrent = kmh == currentKmh,
                            onClick = { onPick(kmh) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    EonaText(
        "La limitation ne change pas tout de suite : elle est mise à jour quand d'autres conducteurs signalent la même.",
        style = EonaTheme.typography.footnote,
        color = colors.textTertiary,
    )
}

/** "50 → 70": what changes, sent by itself when the countdown runs out. */
@Composable
private fun LimitConfirm(currentKmh: Int?, newKmh: Int, onBack: () -> Unit, onSend: (Int) -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val countdown = remember { Animatable(1f) }
    var auto by remember { mutableStateOf(true) }
    var sent by remember { mutableStateOf(false) }

    fun send() {
        if (sent) return
        sent = true
        onSend(newKmh)
    }

    LaunchedEffect(auto) {
        if (!auto) return@LaunchedEffect
        countdown.animateTo(0f, tween(durationMillis = AUTO_MILLIS, easing = LinearEasing))
        send()
    }

    SheetBackTitle("Nouvelle limitation", onBack = { auto = false; onBack() })
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.lg, Alignment.CenterHorizontally),
    ) {
        CurrentSign(kmh = currentKmh, size = 64.dp)
        EonaIcon(EonaIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, size = 28.dp)
        SpeedLimitSign(limitKmh = newKmh, size = 76.dp)
    }
    EonaText(
        if (newKmh == currentKmh) {
            "Confirmer que la limitation affichée est la bonne."
        } else {
            "Signaler une limitation à $newKmh km/h ici, dans ton sens."
        },
        style = EonaTheme.typography.subhead,
        color = colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
        SheetPill("Envoyer", highlighted = true, onClick = { send() }, modifier = Modifier.weight(1f))
        SheetPill("Changer", highlighted = false, onClick = { auto = false; onBack() }, modifier = Modifier.weight(1f))
    }
    if (auto) {
        AutoSendBar(progress = countdown.value)
    }
}

/** One limit to pick, drawn as the real sign on the disc size of a report tile. */
@Composable
private fun LimitTile(kmh: Int, isCurrent: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EonaTheme.colors
    Column(
        modifier = modifier
            .clip(EonaTheme.shapes.lg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = EonaTheme.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm),
    ) {
        SpeedLimitSign(limitKmh = kmh, size = 60.dp)
        EonaText(
            if (isCurrent) "Actuelle" else "$kmh km/h",
            style = EonaTheme.typography.caption,
            color = if (isCurrent) colors.accent else colors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** The limit shown now, or the empty sign when none is known. */
@Composable
private fun CurrentSign(kmh: Int?, size: Dp) {
    if (kmh != null) SpeedLimitSign(limitKmh = kmh, size = size) else UnknownLimitSign(size = size)
}

private const val PER_ROW = 3
private const val AUTO_MILLIS = 5_000
