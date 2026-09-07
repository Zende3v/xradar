package com.xradar.app.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xradar.app.core.model.TripRecord
import com.xradar.app.data.stats.TripHistoryRepository
import com.xradar.app.designsystem.component.XRadarBadge
import com.xradar.app.designsystem.component.XRadarCard
import com.xradar.app.designsystem.component.XRadarMessageState
import com.xradar.app.designsystem.component.XRadarScreenScaffold
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme

@Composable
fun HistoryRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val state: HistoryUiState = remember {
        val trips = TripHistoryRepository(context).all()
        if (trips.isEmpty()) HistoryUiState.Empty else HistoryUiState.Content(trips)
    }
    HistoryScreen(state = state, onBack = onBack)
}

@Composable
fun HistoryScreen(state: HistoryUiState, onBack: () -> Unit) {
    XRadarScreenScaffold(title = "Historique", onBack = onBack) {
        when (state) {
            HistoryUiState.Loading -> LoadingState()
            HistoryUiState.Empty -> EmptyState()
            is HistoryUiState.Content -> TripList(state.trips)
        }
    }
}

@Composable
private fun TripList(trips: List<TripRecord>) {
    val spacing = XRadarTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.lg,
            end = spacing.lg,
            top = spacing.sm,
            bottom = spacing.xxxl,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        items(items = trips, key = { it.id }) { trip -> TripCard(trip) }
    }
}

@Composable
private fun TripCard(trip: TripRecord) {
    val colors = XRadarTheme.colors
    XRadarCard {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.hair),
            ) {
                XRadarText(trip.toLabel, style = XRadarTheme.typography.headline, color = colors.textPrimary)
                XRadarText("Depuis ${trip.fromLabel}", style = XRadarTheme.typography.subhead, color = colors.textSecondary)
                XRadarText(trip.dateLabel, style = XRadarTheme.typography.footnote, color = colors.textTertiary)
            }
            Column(horizontalAlignment = Alignment.End) {
                XRadarText(trip.distanceLabel, style = XRadarTheme.typography.numeric, color = colors.textPrimary)
                XRadarText(trip.durationLabel, style = XRadarTheme.typography.caption, color = colors.textTertiary)
            }
        }
        Row(
            modifier = Modifier.padding(top = XRadarTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(XRadarTheme.spacing.sm),
        ) {
            XRadarBadge("${trip.alertsCount} alertes", color = colors.warning)
            XRadarBadge("max ${trip.topSpeedKmh} km/h", color = colors.textSecondary)
        }
    }
}

@Composable
private fun EmptyState() {
    XRadarMessageState(
        icon = XRadarIcons.History,
        title = "Aucun trajet",
        message = "Tes trajets terminés apparaîtront ici.",
    )
}

@Composable
private fun LoadingState() {
    val spacing = XRadarTheme.spacing
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        repeat(4) { SkeletonCard() }
    }
}

@Composable
private fun SkeletonCard() {
    XRadarCard {
        SkeletonBar(widthFraction = 0.6f)
        Box(Modifier.height(XRadarTheme.spacing.sm))
        SkeletonBar(widthFraction = 0.4f)
        Box(Modifier.height(XRadarTheme.spacing.md))
        SkeletonBar(widthFraction = 0.25f)
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(12.dp)
            .clip(XRadarTheme.shapes.xs)
            .background(XRadarTheme.colors.surfaceHigh),
    )
}

private val SampleTrips = listOf(
    TripRecord("1", System.currentTimeMillis() - 3_600_000L, "Ma position", "Bureau — Montpellier", 12_400, 1_080, 3, 132),
    TripRecord("2", System.currentTimeMillis() - 93_600_000L, "Ma position", "Aéroport Nice Côte d'Azur", 298_000, 10_440, 11, 138),
    TripRecord("3", System.currentTimeMillis() - 180_000_000L, "Ma position", "Avignon", 46_000, 2_280, 2, 128),
)

@Preview(name = "Historique · contenu", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun HistoryContentPreview() {
    XRadarTheme(darkTheme = true) { HistoryScreen(HistoryUiState.Content(SampleTrips), onBack = {}) }
}

@Preview(name = "Historique · vide", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun HistoryEmptyPreview() {
    XRadarTheme(darkTheme = true) { HistoryScreen(HistoryUiState.Empty, onBack = {}) }
}

@Preview(name = "Historique · chargement", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun HistoryLoadingPreview() {
    XRadarTheme(darkTheme = true) { HistoryScreen(HistoryUiState.Loading, onBack = {}) }
}
