package com.xradar.app.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xradar.app.core.model.Place
import com.xradar.app.core.model.PlaceKind
import com.xradar.app.data.geocoding.GeocodingRepository
import com.xradar.app.data.recents.RecentsRepository
import com.xradar.app.data.routing.ActiveTripRepository
import com.xradar.app.designsystem.component.XRadarDivider
import com.xradar.app.designsystem.component.XRadarListGroup
import com.xradar.app.designsystem.component.XRadarListRow
import com.xradar.app.designsystem.component.XRadarLoadingState
import com.xradar.app.designsystem.component.XRadarMessageState
import com.xradar.app.designsystem.component.XRadarSearchField
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.foundation.XRadarIcons
import com.xradar.app.designsystem.theme.XRadarTheme
import kotlinx.coroutines.delay

@Composable
fun SearchRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val geocoding = remember { GeocodingRepository() }
    val recentsRepo = remember { RecentsRepository(context) }
    val recents = remember { recentsRepo.recents() }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    // Debounced live geocoding (French Base Adresse Nationale).
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < MIN_QUERY) {
            results = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        delay(DEBOUNCE_MS)
        results = geocoding.search(q)
        loading = false
    }

    SearchScreen(
        query = query,
        recents = recents,
        suggestions = Suggestions,
        results = results,
        loading = loading,
        onQueryChange = { query = it },
        onPick = { place ->
            recentsRepo.add(place)
            ActiveTripRepository.setDestination(place)
            onBack()
        },
        onBack = onBack,
    )
}

@Composable
fun SearchScreen(
    query: String,
    recents: List<Place>,
    suggestions: List<Place>,
    results: List<Place>,
    loading: Boolean,
    onQueryChange: (String) -> Unit,
    onPick: (Place) -> Unit,
    onBack: () -> Unit,
) {
    val colors = XRadarTheme.colors
    val spacing = XRadarTheme.spacing

    Column(modifier = Modifier.fillMaxSize().background(colors.canvas)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = spacing.lg, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            XRadarSearchField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                autoFocus = true,
            )
            XRadarText(
                text = "Annuler",
                style = XRadarTheme.typography.label,
                color = colors.accent,
                modifier = Modifier
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() }
                    .padding(vertical = spacing.sm),
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                query.trim().length < MIN_QUERY -> BlankState(recents, suggestions, onPick)
                loading -> XRadarLoadingState(label = "Recherche…")
                results.isEmpty() -> EmptyResults(query)
                else -> ResultList(results, onPick)
            }
        }
    }
}

@Composable
private fun BlankState(recents: List<Place>, suggestions: List<Place>, onPick: (Place) -> Unit) {
    val spacing = XRadarTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xl),
    ) {
        if (recents.isNotEmpty()) Group("Récents", recents, onPick)
        Group("Suggestions", suggestions, onPick)
    }
}

@Composable
private fun Group(title: String, places: List<Place>, onPick: (Place) -> Unit) {
    XRadarListGroup(title = title) {
        places.forEachIndexed { index, place ->
            PlaceRow(place, onPick)
            if (index < places.lastIndex) XRadarDivider(Modifier.padding(start = 58.dp))
        }
    }
}

@Composable
private fun ResultList(results: List<Place>, onPick: (Place) -> Unit) {
    val spacing = XRadarTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.lg,
            end = spacing.lg,
            top = spacing.sm,
            bottom = spacing.xxxl,
        ),
    ) {
        items(items = results, key = { it.id }) { place ->
            PlaceRow(place, onPick)
            XRadarDivider(Modifier.padding(start = 58.dp))
        }
    }
}

@Composable
private fun PlaceRow(place: Place, onPick: (Place) -> Unit) {
    XRadarListRow(
        title = place.name,
        subtitle = place.subtitle,
        leadingIcon = place.kind.icon(),
        leadingTint = XRadarTheme.colors.accent,
        onClick = { onPick(place) },
    )
}

@Composable
private fun EmptyResults(query: String) {
    XRadarMessageState(
        icon = XRadarIcons.Search,
        title = "Aucun résultat",
        message = "Rien ne correspond à « $query ».",
    )
}

private fun PlaceKind.icon(): ImageVector = when (this) {
    PlaceKind.Home -> XRadarIcons.Home
    PlaceKind.Work -> XRadarIcons.Flag
    PlaceKind.Favorite -> XRadarIcons.Star
    PlaceKind.Recent -> XRadarIcons.History
    PlaceKind.Result -> XRadarIcons.MapPin
}

private const val MIN_QUERY = 3
private const val DEBOUNCE_MS = 300L

private val Suggestions = listOf(
    Place("montpellier", "Montpellier", "Hérault, Occitanie", PlaceKind.Result, 43.6108, 3.8767),
    Place("paris", "Paris", "Île-de-France", PlaceKind.Result, 48.8566, 2.3522),
    Place("lyon", "Lyon", "Auvergne-Rhône-Alpes", PlaceKind.Result, 45.7640, 4.8357),
    Place("marseille", "Marseille", "Provence-Alpes-Côte d'Azur", PlaceKind.Result, 43.2965, 5.3698),
    Place("nimes", "Nîmes", "Gard, Occitanie", PlaceKind.Result, 43.8367, 4.3601),
)

@Preview(name = "Recherche · suggestions", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun SearchSuggestionsPreview() {
    XRadarTheme(darkTheme = true) {
        SearchScreen("", emptyList(), Suggestions, emptyList(), false, {}, {}, {})
    }
}
