package com.xradar.app.feature.menu

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
import com.xradar.app.core.model.TripRecord
import com.xradar.app.data.account.AccountRepository
import com.xradar.app.data.account.AccountStats
import com.xradar.app.designsystem.component.XRadarCard
import com.xradar.app.designsystem.component.XRadarDivider
import com.xradar.app.designsystem.component.XRadarIcon
import com.xradar.app.designsystem.component.XRadarListGroup
import com.xradar.app.designsystem.component.XRadarListRow
import com.xradar.app.designsystem.component.XRadarLoadingState
import com.xradar.app.designsystem.component.XRadarMessageState
import com.xradar.app.designsystem.component.XRadarScreenScaffold
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme

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
    LaunchedEffect(Unit) {
        stats = AccountRepository.stats()
        loaded = true
    }
    XRadarScreenScaffold(title = "Statistiques", onBack = onBack) {
        val s = stats
        when {
            !loaded -> XRadarLoadingState(label = "Chargement…")
            s == null -> XRadarMessageState(
                icon = XRadarIcons.Info,
                title = "Statistiques indisponibles",
                message = "Impossible de joindre le serveur. Réessaie plus tard.",
            )
            else -> StatsContent(s, onOpenTrip = { selected = it })
        }
    }
}

@Composable
private fun StatsContent(s: AccountStats, onOpenTrip: (TripRecord) -> Unit) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing
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

        XRadarListGroup(title = "Signaleur") {
            XRadarListRow(title = "Note de confiance", trailing = { TrustStars(s.trust) })
            XRadarDivider(Modifier.padding(start = spacing.lg))
            Figure("Alertes traversées", s.alertsTraversed)
            XRadarDivider(Modifier.padding(start = spacing.lg))
            Figure("Signalements déclarés", s.reportsDeclared)
            XRadarDivider(Modifier.padding(start = spacing.lg))
            Figure("Confirmés par d'autres", s.reportsConfirmed)
        }
        XRadarText(
            "La note compare tes signalements confirmés à ceux que tu as déclarés. " +
                "Elle démarre à 2,5 et monte à mesure que la communauté valide ce que tu signales.",
            style = XRadarTheme.typography.footnote,
            color = colors.textTertiary,
        )

        XRadarListGroup(title = "Historique des trajets") {
            if (s.trips.isEmpty()) {
                XRadarListRow(title = "Aucun trajet pour l'instant")
            } else {
                s.trips.take(MAX_TRIPS).forEachIndexed { i, trip ->
                    TripRow(trip, onClick = { onOpenTrip(trip) })
                    if (i < s.trips.take(MAX_TRIPS).lastIndex) XRadarDivider(Modifier.padding(start = spacing.lg))
                }
            }
        }
        Spacer(Modifier.height(spacing.xxl))
    }
}

@Composable
private fun Tile(value: String, label: String, valueColor: Color, modifier: Modifier) {
    XRadarCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.xs),
        ) {
            XRadarText(value, style = XRadarTheme.typography.title, color = valueColor, maxLines = 1)
            XRadarText(label.uppercase(), style = XRadarTheme.typography.caption, color = XRadarTheme.colors.textTertiary)
        }
    }
}

@Composable
private fun Figure(label: String, value: Int) {
    XRadarListRow(
        title = label,
        trailing = {
            XRadarText(grouped(value), style = XRadarTheme.typography.callout, color = XRadarTheme.colors.textPrimary)
        },
    )
}

@Composable
private fun TripRow(trip: TripRecord, onClick: () -> Unit) {
    XRadarListRow(
        title = trip.toLabel,
        subtitle = "${trip.dateLabel} · ${trip.distanceLabel} · ${trip.durationLabel}",
        onClick = onClick,
        trailing = {
            trip.delayLabel?.let {
                XRadarText(it, style = XRadarTheme.typography.caption, color = XRadarTheme.colors.textTertiary)
            }
            XRadarIcon(XRadarIcons.ChevronRight, contentDescription = null, tint = XRadarTheme.colors.textTertiary, size = 20.dp)
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
