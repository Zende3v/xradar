package com.eona.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eona.app.data.preferences.AppPreferences
import com.eona.app.data.preferences.VehicleType
import com.eona.app.designsystem.component.EonaListGroup
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.drive.component.VehicleGlyph

/**
 * "Véhicule": the drawing of the driver's own position on the map, as the map draws it. Kept on
 * this phone only; nothing else changes with it (routing, speed, statistics, rights).
 */
@Composable
fun VehiclePicker() {
    val settings by AppPreferences.settings.collectAsStateWithLifecycle()
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        EonaListGroup(title = "Véhicule") {
            Row(
                modifier = Modifier
                    .padding(spacing.md)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                VehicleType.entries.forEach { type ->
                    val chosen = settings.vehicleType == type
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(EonaTheme.shapes.md)
                            .background(if (chosen) colors.accent.copy(alpha = 0.18f) else colors.surface)
                            .border(1.dp, if (chosen) colors.accent else colors.border, EonaTheme.shapes.md)
                            .selectable(selected = chosen, role = Role.RadioButton) {
                                AppPreferences.updateSettings { it.copy(vehicleType = type) }
                            }
                            .padding(vertical = spacing.sm),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        VehicleGlyph(type, if (chosen) colors.accent else colors.textTertiary, Modifier.size(GLYPH_SIZE))
                        EonaText(
                            type.label,
                            style = EonaTheme.typography.callout,
                            color = if (chosen) colors.accent else colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        EonaText(
            "Le curseur de ta position sur la carte. Réglage gardé sur ce téléphone.",
            style = EonaTheme.typography.footnote,
            color = colors.textTertiary,
            modifier = Modifier.padding(horizontal = spacing.md),
        )
    }
}

private val GLYPH_SIZE = 44.dp
