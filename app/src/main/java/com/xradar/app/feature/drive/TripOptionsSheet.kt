package com.xradar.app.feature.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xradar.app.core.model.TripInfo
import com.xradar.app.data.preferences.AppPreferences
import com.xradar.app.designsystem.component.XRadarBadge
import com.xradar.app.designsystem.component.XRadarDivider
import com.xradar.app.designsystem.component.XRadarListGroup
import com.xradar.app.designsystem.component.XRadarListRow
import com.xradar.app.designsystem.component.XRadarSwitch
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme

/** "E1" — Waze-style trip menu: trip time summary + route options + alert config.
 *  Opened by tapping the Options button on the HUD. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripOptionsSheet(trip: TripInfo?, onDismiss: () -> Unit) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    val prefs by AppPreferences.alerts.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()

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
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            XRadarText("Options du trajet", style = XRadarTheme.typography.title, color = colors.textPrimary)

            if (trip != null) {
                TripSummary(trip)
            }

            XRadarListGroup(title = "Itinéraire") {
                RouteOption("Éviter les péages", XRadarIcons.Toll, colors.controlZone)
                RowDivider()
                RouteOption("Éviter les autoroutes", XRadarIcons.Navigation, colors.accent)
            }

            XRadarListGroup(title = "Alertes") {
                Toggle("Radar fixe", XRadarIcons.Radar, colors.radarFixed, prefs.radarFixed) {
                    AppPreferences.updateAlerts { it.copy(radarFixed = !it.radarFixed) }
                }
                RowDivider()
                Toggle("Radar mobile", XRadarIcons.Radar, colors.radarMobile, prefs.radarMobile) {
                    AppPreferences.updateAlerts { it.copy(radarMobile = !it.radarMobile) }
                }
                RowDivider()
                Toggle("Caméra (voie publique)", XRadarIcons.Camera, colors.radarFixed, prefs.cameras) {
                    AppPreferences.updateAlerts { it.copy(cameras = !it.cameras) }
                }
                RowDivider()
                Toggle("Zone de contrôle", XRadarIcons.Shield, colors.controlZone, prefs.controlZones) {
                    AppPreferences.updateAlerts { it.copy(controlZones = !it.controlZones) }
                }
                RowDivider()
                Toggle("Danger & travaux", XRadarIcons.Warning, colors.hazard, prefs.hazards) {
                    AppPreferences.updateAlerts { it.copy(hazards = !it.hazards) }
                }
            }

            XRadarListGroup(title = "Notifications") {
                Toggle("Annonces vocales", XRadarIcons.VolumeHigh, colors.accent, prefs.voice) {
                    AppPreferences.updateAlerts { it.copy(voice = !it.voice) }
                }
                RowDivider()
                Toggle("Son des alertes", XRadarIcons.Bell, colors.accent, prefs.sound) {
                    AppPreferences.updateAlerts { it.copy(sound = !it.sound) }
                }
                RowDivider()
                Toggle("Vibration", XRadarIcons.Bell, colors.accent, prefs.vibration) {
                    AppPreferences.updateAlerts { it.copy(vibration = !it.vibration) }
                }
            }

            XRadarText(
                "Les options d'itinéraire arrivent bientôt.",
                style = XRadarTheme.typography.footnote,
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun TripSummary(trip: TripInfo) {
    val colors = XRadarTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.md),
    ) {
        SummaryCluster(trip.remainingLabel, "Restantes", colors.accent, Modifier.weight(1f))
        SummaryCluster(trip.distanceLabel, "Distance", colors.textPrimary, Modifier.weight(1f))
        SummaryCluster(trip.arrivalLabel, "Arrivée", colors.textPrimary, Modifier.weight(1f))
    }
}

@Composable
private fun SummaryCluster(
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier,
) {
    Column(modifier) {
        XRadarText(value, style = XRadarTheme.typography.title, color = valueColor)
        XRadarText(label.uppercase(), style = XRadarTheme.typography.caption, color = XRadarTheme.colors.textTertiary)
    }
}

@Composable
private fun RouteOption(title: String, icon: ImageVector, tint: Color) {
    XRadarListRow(
        title = title,
        leadingIcon = icon,
        leadingTint = tint,
        trailing = { XRadarBadge("Bientôt", color = XRadarTheme.colors.textTertiary) },
    )
}

@Composable
private fun Toggle(
    title: String,
    icon: ImageVector,
    tint: Color,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    XRadarListRow(
        title = title,
        leadingIcon = icon,
        leadingTint = tint,
        onClick = onToggle,
        trailing = { XRadarSwitch(checked = checked, onCheckedChange = { onToggle() }) },
    )
}

@Composable
private fun RowDivider() {
    XRadarDivider(Modifier.padding(start = 58.dp))
}
