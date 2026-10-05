package com.eona.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eona.app.data.preferences.VehicleType
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.drive.component.VehicleGlyph

/**
 * « Véhicule » : une carte par véhicule, son curseur sur la carte et ses limites ; le choix cerclé
 * d'accent. Grille de 3, comme iOS (VehiclePicker.swift).
 */
@Composable
fun VehiclePicker(selection: VehicleType, onPick: (VehicleType) -> Unit) {
    val spacing = EonaTheme.spacing
    Column(
        modifier = Modifier
            .padding(spacing.md)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        VehicleType.entries.chunked(PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                row.forEach { type -> VehicleCard(type, type == selection, { onPick(type) }, Modifier.weight(1f)) }
                // La dernière ligne garde la taille des autres.
                repeat(PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun VehicleCard(type: VehicleType, chosen: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Column(
        modifier = modifier
            .clip(EonaTheme.shapes.md)
            .background(if (chosen) colors.accent.copy(alpha = 0.14f) else colors.surface)
            .border(if (chosen) 1.5.dp else 1.dp, if (chosen) colors.accent else colors.border, EonaTheme.shapes.md)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = spacing.md, horizontal = spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        VehicleGlyph(type, if (chosen) colors.accent else colors.textTertiary, Modifier.size(GLYPH_SIZE))
        EonaText(
            type.label,
            style = EonaTheme.typography.caption,
            color = if (chosen) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
        )
        EonaText(
            type.rules,
            style = EonaTheme.typography.caption.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal),
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
        )
    }
}

/** Scooter 50, sans permis : limites et routes ; autres : curseur seul. */
private val VehicleType.rules: String
    get() = if (moped) "45 km/h max\nSans voie rapide" else "Esthétique"

private const val PER_ROW = 3
private val GLYPH_SIZE = 40.dp
