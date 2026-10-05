package com.eona.app.feature.menu

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eona.app.core.model.TripRecord
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.account.AccountStats
import com.eona.app.data.stats.TripHistoryRepository
import com.eona.app.designsystem.component.EonaCard
import com.eona.app.designsystem.component.EonaDivider
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaListGroup
import com.eona.app.designsystem.component.EonaListRow
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme

/**
 * Statistiques, dans « Mon compte & Statistiques » (iOS StatsSections) : temps et distance sur la
 * route, qualité de signaleur, historique des trajets. Tout vient du serveur : une réinstallation
 * n'efface rien. Chargement et erreur en ligne ; l'écran parent charge ([loadStats]).
 */
@Composable
fun StatsSections(stats: AccountStats?, loaded: Boolean, onOpenTrip: (TripRecord) -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    when {
        !loaded -> EonaListGroup(title = "Statistiques") {
            Row(
                modifier = Modifier.padding(spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), color = colors.textSecondary, strokeWidth = 2.dp)
                EonaText("Chargement…", style = EonaTheme.typography.footnote, color = colors.textSecondary)
            }
        }
        stats == null -> EonaListGroup(title = "Statistiques") {
            EonaText(
                "Serveur injoignable. Réessaie plus tard.",
                style = EonaTheme.typography.footnote,
                color = colors.textSecondary,
                modifier = Modifier.padding(spacing.lg),
            )
        }
        else -> StatsContent(stats, onOpenTrip)
    }
}

/** Statistiques du serveur ; null : injoignable. Le rang d'un trajet en groupe vient du téléphone. */
suspend fun loadStats(context: Context): AccountStats? {
    val groups = TripHistoryRepository(context).groupResults()
    return AccountRepository.stats()?.let { s ->
        s.copy(trips = s.trips.map { trip -> groups[trip.id]?.let { trip.copy(group = it) } ?: trip })
    }
}

@Composable
private fun StatsContent(s: AccountStats, onOpenTrip: (TripRecord) -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        EonaText("STATISTIQUES", style = EonaTheme.typography.caption, color = colors.textTertiary, modifier = Modifier.padding(horizontal = spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            Tile(hoursLabel(s.driveSeconds), "Sur la route", colors.accent, Modifier.weight(1f))
            Tile(kmLabel(s.distanceMeters), "Parcourus", colors.textPrimary, Modifier.weight(1f))
            Tile(s.tripCount.toString(), "Trajets", colors.textPrimary, Modifier.weight(1f))
        }
    }

    EonaListGroup(title = "Signaleur") {
        EonaListRow(title = "Note de confiance", trailing = { TrustStars(s.trust) })
        EonaDivider(Modifier.padding(start = spacing.lg))
        Figure("Alertes traversées", s.alertsTraversed)
        EonaDivider(Modifier.padding(start = spacing.lg))
        Figure("Signalements déclarés", s.reportsDeclared)
        EonaDivider(Modifier.padding(start = spacing.lg))
        Figure("Confirmés par d'autres", s.reportsConfirmed)
    }
    EonaListGroup(title = "Historique des trajets") {
        if (s.trips.isEmpty()) {
            EonaListRow(title = "Aucun trajet pour l'instant")
        } else {
            val shown = s.trips.take(MAX_TRIPS)
            shown.forEachIndexed { i, trip ->
                TripRow(trip, onClick = { onOpenTrip(trip) })
                if (i < shown.lastIndex) EonaDivider(Modifier.padding(start = spacing.lg))
            }
        }
    }
}

@Composable
private fun Tile(value: String, label: String, valueColor: Color, modifier: Modifier) {
    EonaCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.xs),
        ) {
            EonaText(value, style = EonaTheme.typography.title, color = valueColor, maxLines = 1)
            EonaText(label.uppercase(), style = EonaTheme.typography.caption, color = EonaTheme.colors.textTertiary, maxLines = 1)
        }
    }
}

@Composable
private fun Figure(label: String, value: Int) {
    EonaListRow(
        title = label,
        trailing = {
            EonaText(grouped(value), style = EonaTheme.typography.callout, color = EonaTheme.colors.textPrimary)
        },
    )
}

@Composable
private fun TripRow(trip: TripRecord, onClick: () -> Unit) {
    EonaListRow(
        title = trip.toLabel,
        subtitle = "${trip.dateLabel} · ${trip.distanceLabel} · ${trip.durationLabel}",
        onClick = onClick,
        leadingIcon = if (trip.group != null) EonaIcons.People else null,
        glow = trip.group != null,
        trailing = {
            val group = trip.group
            if (group != null) {
                // A group trip: the rank takes the place of the gap to the estimate.
                EonaText(group.standingLabel, style = EonaTheme.typography.caption, color = EonaTheme.colors.accent)
            } else {
                trip.delayLabel?.let {
                    EonaText(it, style = EonaTheme.typography.caption, color = EonaTheme.colors.textTertiary)
                }
            }
            EonaIcon(EonaIcons.ChevronRight, contentDescription = null, tint = EonaTheme.colors.textTertiary, size = 20.dp)
        },
    )
}

/**
 * Time on the road, in minutes then hours only — never days: 2 460 h stays "2460h",
 * because that is the number a driver compares, not 102,5 days.
 */
fun hoursLabel(seconds: Long): String {
    val minutes = seconds / 60
    return when {
        minutes < 60 -> "$minutes min"
        minutes < 600 -> "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}"
        else -> "${minutes / 60}h"
    }
}

private fun kmLabel(meters: Long): String {
    val km = meters / 1000.0
    return if (km < 10) "%.1f km".format(km).replace('.', ',') else "${grouped(km.toInt())} km"
}

/** 3240 -> "3 240". */
private fun grouped(value: Int): String = value.toString().reversed().chunked(3).joinToString(" ").reversed()

private const val MAX_TRIPS = 30
