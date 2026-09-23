package com.eona.app.feature.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eona.app.core.model.TripRecord
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.account.AccountStats
import com.eona.app.data.stats.TripHistoryRepository
import androidx.compose.ui.platform.LocalContext
import com.eona.app.designsystem.component.EonaCard
import com.eona.app.designsystem.component.EonaDivider
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaListGroup
import com.eona.app.designsystem.component.EonaListRow
import com.eona.app.designsystem.component.EonaLoadingState
import com.eona.app.designsystem.component.EonaMessageState
import com.eona.app.designsystem.component.EonaScreenScaffold
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme

/**
 * Statistiques: time and distance on the road with the app, how good a reporter you
 * are (alerts crossed / reports filed / reports others confirmed), trip history.
 * Everything comes from the server, so it survives a reinstall.
 */
@Composable
fun StatsRoute(onBack: () -> Unit) {
    var stats by remember { mutableStateOf<AccountStats?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<TripRecord?>(null) }
    selected?.let { trip ->
        TripDetailScreen(trip, onBack = { selected = null })
        return
    }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        // The server keeps the trips; a group trip's ranking stays on this phone, joined by id.
        val groups = TripHistoryRepository(context).groupResults()
        stats = AccountRepository.stats()?.let { s ->
            s.copy(trips = s.trips.map { trip -> groups[trip.id]?.let { trip.copy(group = it) } ?: trip })
        }
        loaded = true
    }
    EonaScreenScaffold(title = "Statistiques", onBack = onBack) {
        val s = stats
        when {
            !loaded -> EonaLoadingState(label = "Chargement…")
            s == null -> EonaMessageState(
                icon = EonaIcons.Info,
                title = "Statistiques indisponibles",
                message = "Impossible de joindre le serveur. Réessaie plus tard.",
            )
            else -> StatsContent(s, onOpenTrip = { selected = it })
        }
    }
}

@Composable
private fun StatsContent(s: AccountStats, onOpenTrip: (TripRecord) -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xl),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            Tile(hoursLabel(s.driveSeconds), "Sur la route", colors.accent, Modifier.weight(1f))
            Tile(kmLabel(s.distanceMeters), "Parcourus", colors.textPrimary, Modifier.weight(1f))
            Tile(s.tripCount.toString(), "Trajets", colors.textPrimary, Modifier.weight(1f))
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
                s.trips.take(MAX_TRIPS).forEachIndexed { i, trip ->
                    TripRow(trip, onClick = { onOpenTrip(trip) })
                    if (i < s.trips.take(MAX_TRIPS).lastIndex) EonaDivider(Modifier.padding(start = spacing.lg))
                }
            }
        }
        Spacer(Modifier.height(spacing.xxl))
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
            EonaText(label.uppercase(), style = EonaTheme.typography.caption, color = EonaTheme.colors.textTertiary)
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
