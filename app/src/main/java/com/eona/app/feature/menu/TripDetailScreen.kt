package com.eona.app.feature.menu

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
import com.eona.app.core.model.AlertType
import com.eona.app.core.model.TripRecord
import com.eona.app.designsystem.component.EonaBadge
import com.eona.app.designsystem.component.EonaCard
import com.eona.app.designsystem.component.EonaDivider
import com.eona.app.designsystem.component.EonaListGroup
import com.eona.app.designsystem.component.EonaListRow
import com.eona.app.designsystem.component.EonaScreenScaffold
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.feature.drive.component.icon

/**
 * One trip of the history: the real time against the estimate, distance, speeds, stops, and the
 * alerts met on the way. Time in traffic jams comes later.
 */
@Composable
fun TripDetailScreen(trip: TripRecord, onBack: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    BackHandler(onBack = onBack)
    EonaScreenScaffold(title = "Trajet", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            EonaCard {
                EonaText(trip.toLabel, style = EonaTheme.typography.title, color = colors.textPrimary)
                EonaText(trip.dateLabel, style = EonaTheme.typography.subhead, color = colors.textSecondary)
            }

            EonaListGroup(title = "Temps") {
                Info("Temps réel", trip.durationLabel)
                Divider()
                Info("Temps prévu", trip.plannedLabel ?: "Inconnu")
                trip.delayLabel?.let {
                    Divider()
                    Info("Écart", it)
                }
            }

            EonaListGroup(title = "Conduite") {
                Info("Kilomètres", trip.distanceLabel)
                Divider()
                Info("Vitesse moyenne", "${trip.averageSpeedKmh} km/h")
                Divider()
                Info("Vitesse max", "${trip.topSpeedKmh} km/h")
                Divider()
                Info("Arrêts", trip.stopsLabel)
                Divider()
                EonaListRow(title = "Temps dans les bouchons", trailing = { EonaBadge("Bientôt", glow = true) })
            }

            trip.group?.let { group ->
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    EonaListGroup(title = "Trajet en groupe") {
                        Info("Mon rang", group.standingLabel)
                        Divider()
                        Info("Code du groupe", group.code)
                        group.ranking.forEach { entry ->
                            Divider()
                            EonaListRow(
                                title = if (entry.me) "${entry.name} (moi)" else entry.name,
                                trailing = {
                                    EonaText(
                                        "${entry.rankLabel} · ${entry.timeLabel}",
                                        style = EonaTheme.typography.callout,
                                        color = if (entry.me) colors.accent else colors.textSecondary,
                                    )
                                },
                            )
                        }
                    }
                    EonaText(
                        "Le classement a été figé à la fin du trajet. Des autres participants, seuls leur pseudo, leur rang et leur temps sont conservés.",
                        style = EonaTheme.typography.footnote,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(horizontal = spacing.md),
                    )
                }
            }

            EonaListGroup(title = "Événements rencontrés") {
                val kinds = AlertType.entries.filter { (trip.events[it] ?: 0) > 0 }
                if (kinds.isEmpty()) {
                    // Trips recorded before the detail was kept only have a total.
                    EonaListRow(title = if (trip.alertsCount > 0) "${trip.alertsCount} alertes" else "Aucun")
                } else {
                    kinds.forEachIndexed { i, type ->
                        if (i > 0) Divider()
                        EonaListRow(
                            title = type.tripLabel,
                            leadingIcon = type.icon(),
                            glow = true,
                            trailing = {
                                EonaText("${trip.events[type] ?: 0}", style = EonaTheme.typography.callout, color = colors.textSecondary)
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
    EonaListRow(
        title = title,
        trailing = { EonaText(value, style = EonaTheme.typography.callout, color = EonaTheme.colors.textSecondary) },
    )
}

@Composable
private fun Divider() = EonaDivider(Modifier.padding(start = EonaTheme.spacing.lg))

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
