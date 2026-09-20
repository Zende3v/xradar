package com.eona.app.feature.history

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
import com.eona.app.core.model.TripRecord
import com.eona.app.data.stats.TripHistoryRepository
import com.eona.app.designsystem.component.EonaBadge
import com.eona.app.designsystem.component.EonaCard
import com.eona.app.designsystem.component.EonaMessageState
import com.eona.app.designsystem.component.EonaScreenScaffold
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme

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
    EonaScreenScaffold(title = "Historique", onBack = onBack) {
        when (state) {
            HistoryUiState.Loading -> LoadingState()
            HistoryUiState.Empty -> EmptyState()
            is HistoryUiState.Content -> TripList(state.trips)
        }
    }
}

@Composable
private fun TripList(trips: List<TripRecord>) {
    val spacing = EonaTheme.spacing
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
    val colors = EonaTheme.colors
    EonaCard {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(EonaTheme.spacing.hair),
            ) {
                EonaText(trip.toLabel, style = EonaTheme.typography.headline, color = colors.textPrimary)
                EonaText("Depuis ${trip.fromLabel}", style = EonaTheme.typography.subhead, color = colors.textSecondary)
                EonaText(trip.dateLabel, style = EonaTheme.typography.footnote, color = colors.textTertiary)
            }
            Column(horizontalAlignment = Alignment.End) {
                EonaText(trip.distanceLabel, style = EonaTheme.typography.numeric, color = colors.textPrimary)
                EonaText(trip.durationLabel, style = EonaTheme.typography.caption, color = colors.textTertiary)
            }
        }
        Row(
            modifier = Modifier.padding(top = EonaTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm),
        ) {
            EonaBadge("${trip.alertsCount} alertes", color = colors.warning)
            EonaBadge("max ${trip.topSpeedKmh} km/h", color = colors.textSecondary)
        }
    }
}

@Composable
private fun EmptyState() {
    EonaMessageState(
        icon = EonaIcons.History,
        title = "Aucun trajet",
        message = "Tes trajets terminés apparaîtront ici.",
    )
}

@Composable
private fun LoadingState() {
    val spacing = EonaTheme.spacing
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        repeat(4) { SkeletonCard() }
    }
}

@Composable
private fun SkeletonCard() {
    EonaCard {
        SkeletonBar(widthFraction = 0.6f)
        Box(Modifier.height(EonaTheme.spacing.sm))
        SkeletonBar(widthFraction = 0.4f)
        Box(Modifier.height(EonaTheme.spacing.md))
        SkeletonBar(widthFraction = 0.25f)
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(12.dp)
            .clip(EonaTheme.shapes.xs)
            .background(EonaTheme.colors.surfaceHigh),
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
    EonaTheme(darkTheme = true) { HistoryScreen(HistoryUiState.Content(SampleTrips), onBack = {}) }
}

@Preview(name = "Historique · vide", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun HistoryEmptyPreview() {
    EonaTheme(darkTheme = true) { HistoryScreen(HistoryUiState.Empty, onBack = {}) }
}

@Preview(name = "Historique · chargement", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun HistoryLoadingPreview() {
    EonaTheme(darkTheme = true) { HistoryScreen(HistoryUiState.Loading, onBack = {}) }
}
