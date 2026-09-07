package com.xradar.app.feature.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xradar.app.core.model.ReportType
import com.xradar.app.core.model.Role
import com.xradar.app.data.account.AccountRepository
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.feature.drive.component.color
import com.xradar.app.feature.drive.component.icon
import com.xradar.app.designsystem.theme.XRadarTheme

/**
 * Quick-pick report sheet, role-gated: only categories the account may create are
 * shown. Camera needs a street + side; radar-car (admin) needs a plate; everything
 * else posts on tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(onReport: OnReport, onDismiss: () -> Unit) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    val sheetState = rememberModalBottomSheetState()
    val account by AccountRepository.account.collectAsStateWithLifecycle()
    val role = account?.role ?: Role.Guest
    val available = ReportType.entries.filter { it.allowedFor(role) }
    var selected by remember { mutableStateOf<ReportType?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceElevated,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(modifier = Modifier.fillMaxWidth().padding(top = spacing.md), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .clip(XRadarTheme.shapes.pill)
                        .background(colors.borderStrong),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            val current = selected
            if (current == null) {
                XRadarText("Signaler", style = XRadarTheme.typography.title, color = colors.textPrimary)
                available.chunked(3).forEach { rowTypes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                        rowTypes.forEach { type ->
                            ReportTile(
                                type = type,
                                onClick = {
                                    if (type.needsStreet || type.needsPlate) {
                                        selected = type
                                    } else {
                                        onReport(type, null, null, null)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - rowTypes.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                XRadarText(
                    "Le signalement est partagé avec les autres conducteurs à proximité.",
                    style = XRadarTheme.typography.footnote,
                    color = colors.textTertiary,
                )
            } else {
                DetailForm(type = current, onBack = { selected = null }, onReport = onReport)
            }
        }
    }
}

@Composable
private fun DetailForm(type: ReportType, onBack: () -> Unit, onReport: OnReport) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    var street by remember { mutableStateOf("") }
    var plate by remember { mutableStateOf("") }
    var side by remember { mutableStateOf<String?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Box(modifier = Modifier.clip(XRadarTheme.shapes.pill).clickable(onClick = onBack).padding(4.dp)) {
            XRadarIcon(com.xradar.app.designsystem.foundation.XRadarIcons.ChevronLeft, contentDescription = "Retour", tint = colors.textSecondary, size = 22.dp)
        }
        XRadarText(type.label, style = XRadarTheme.typography.title, color = colors.textPrimary)
    }

    if (type.needsPlate) {
        SheetField(value = plate, onChange = { plate = it.uppercase() }, placeholder = "Plaque (ex. AB-123-CD)", caps = KeyboardCapitalization.Characters)
        XRadarText(
            "La plaque reste privée (jamais partagée). Elle sert à situer la zone probable.",
            style = XRadarTheme.typography.footnote,
            color = colors.textTertiary,
        )
    }

    if (type.needsStreet) {
        SheetField(value = street, onChange = { street = it }, placeholder = "Rue (ex. Rue de la Paix)", caps = KeyboardCapitalization.Words)
        XRadarText("Côté de la route", style = XRadarTheme.typography.subhead, color = colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            SidePill("Gauche", side == "left", { side = "left" }, Modifier.weight(1f))
            SidePill("Droite", side == "right", { side = "right" }, Modifier.weight(1f))
        }
    }

    val valid = (!type.needsPlate || plate.isNotBlank()) && (!type.needsStreet || street.isNotBlank())
    ReportConfirm(
        enabled = valid,
        onClick = {
            onReport(
                type,
                plate.trim().ifBlank { null },
                street.trim().ifBlank { null },
                side,
            )
        },
    )
}

@Composable
private fun SheetField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    caps: KeyboardCapitalization,
) {
    val colors = XRadarTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(XRadarTheme.shapes.md)
            .background(colors.surface)
            .border(1.dp, colors.border, XRadarTheme.shapes.md)
            .padding(horizontal = XRadarTheme.spacing.md, vertical = XRadarTheme.spacing.md),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = XRadarTheme.typography.body.merge(androidx.compose.ui.text.TextStyle(color = colors.textPrimary)),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(capitalization = caps),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    XRadarText(placeholder, style = XRadarTheme.typography.body, color = colors.textTertiary)
                }
                inner()
            },
        )
    }
}

@Composable
private fun SidePill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = XRadarTheme.colors
    Box(
        modifier = modifier
            .clip(XRadarTheme.shapes.md)
            .background(if (selected) colors.accent.copy(alpha = 0.16f) else colors.surface)
            .border(1.dp, if (selected) colors.accent else colors.border, XRadarTheme.shapes.md)
            .clickable(onClick = onClick)
            .padding(vertical = XRadarTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        XRadarText(label, style = XRadarTheme.typography.bodyStrong, color = if (selected) colors.accent else colors.textPrimary)
    }
}

@Composable
private fun ReportConfirm(enabled: Boolean, onClick: () -> Unit) {
    val colors = XRadarTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(XRadarTheme.shapes.lg)
            .background(if (enabled) colors.accent else colors.surfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = XRadarTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        XRadarText(
            "Signaler",
            style = XRadarTheme.typography.bodyStrong,
            color = if (enabled) colors.onAccent else colors.textTertiary,
        )
    }
}

@Composable
private fun ReportTile(type: ReportType, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val tint = type.alertType.color()
    Column(
        modifier = modifier
            .clip(XRadarTheme.shapes.lg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = XRadarTheme.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .background(tint.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            XRadarIcon(type.alertType.icon(), contentDescription = null, tint = tint, size = 30.dp)
        }
        XRadarText(
            type.label,
            style = XRadarTheme.typography.caption,
            color = XRadarTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
