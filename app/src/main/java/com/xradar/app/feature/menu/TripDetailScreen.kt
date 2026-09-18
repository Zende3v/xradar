package com.xradar.app.feature.menu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xradar.app.core.model.AlertType
import com.xradar.app.core.model.TripRecord
import com.xradar.app.designsystem.component.XRadarBadge
import com.xradar.app.designsystem.component.XRadarCard
import com.xradar.app.designsystem.component.XRadarDivider
import com.xradar.app.designsystem.component.XRadarListGroup
import com.xradar.app.designsystem.component.XRadarListRow
import com.xradar.app.designsystem.component.XRadarScreenScaffold
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.theme.XRadarTheme
import com.xradar.app.feature.drive.component.icon

/**
 * One trip of the history: the real time against the estimate, distance, speeds, stops, and the
 * alerts met on the way. Time in traffic jams comes later.
 */
@Composable
fun TripDetailScreen(trip: TripRecord, onBack: () -> Unit) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
    BackHandler(onBack = onBack)
    XRadarScreenScaffold(title = "Trajet", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            XRadarCard {
                XRadarText(trip.toLabel, style = XRadarTheme.typography.title, color = colors.textPrimary)
                XRadarText(trip.dateLabel, style = XRadarTheme.typography.subhead, color = colors.textSecondary)
            }

            XRadarListGroup(title = "Temps") {
                Info("Temps réel", trip.durationLabel)
                Divider()
                Info("Temps prévu", trip.plannedLabel ?: "Inconnu")
                trip.delayLabel?.let {
                    Divider()
                    Info("Écart", it)
                }
            }

            XRadarListGroup(title = "Conduite") {
                Info("Kilomètres", trip.distanceLabel)
                Divider()
                Info("Vitesse moyenne", "${trip.averageSpeedKmh} km/h")
                Divider()
                Info("Vitesse max", "${trip.topSpeedKmh} km/h")
                Divider()
                Info("Arrêts", trip.stopsLabel)
                Divider()
                XRadarListRow(title = "Temps dans les bouchons", trailing = { XRadarBadge("Bientôt", glow = true) })
            }

            XRadarListGroup(title = "Événements rencontrés") {
                val kinds = AlertType.entries.filter { (trip.events[it] ?: 0) > 0 }
                if (kinds.isEmpty()) {
                    // Trips recorded before the detail was kept only have a total.
                    XRadarListRow(title = if (trip.alertsCount > 0) "${trip.alertsCount} alertes" else "Aucun")
                } else {
                    kinds.forEachIndexed { i, type ->
                        if (i > 0) Divider()
                        XRadarListRow(
                            title = type.tripLabel,
                            leadingIcon = type.icon(),
                            glow = true,
                            trailing = {
                                XRadarText("${trip.events[type] ?: 0}", style = XRadarTheme.typography.callout, color = colors.textSecondary)
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

@Composable
private fun Info(title: String, value: String) {
    XRadarListRow(
        title = title,
        trailing = { XRadarText(value, style = XRadarTheme.typography.callout, color = XRadarTheme.colors.textSecondary) },
    )
}

@Composable
private fun Divider() = XRadarDivider(Modifier.padding(start = XRadarTheme.spacing.lg))

private val AlertType.tripLabel: String
    get() = when (this) {
        AlertType.RadarFixed -> "Radars fixes"
        AlertType.RadarMobile -> "Radars mobiles"
        AlertType.ControlZone -> "Zones de contrôle"
        AlertType.Camera -> "Caméras"
        AlertType.Hazard -> "Dangers"
        AlertType.Accident -> "Accidents"
        AlertType.Roadwork -> "Travaux"
        AlertType.RadarCar -> "Voitures radar"
    }
