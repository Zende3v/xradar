package com.eona.app.feature.search

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eona.app.R
import com.eona.app.core.model.FuelPrice
import com.eona.app.core.model.FuelType
import com.eona.app.core.model.NearbyLabels
import com.eona.app.core.model.NearbyPicker
import com.eona.app.core.model.NearbyResults
import com.eona.app.core.model.Place
import com.eona.app.core.model.PlaceCategory
import com.eona.app.core.model.PlaceKind
import com.eona.app.core.model.showsFuelPrice
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.search.SearchApi
import com.eona.app.data.places.FavoriteTrip
import com.eona.app.data.places.PlacesApi
import com.eona.app.data.places.SavedPlacesRepository
import com.eona.app.data.preferences.AppPreferences
import com.eona.app.data.recents.RecentsRepository
import com.eona.app.data.routing.ActiveTripRepository
import com.eona.app.designsystem.component.EonaChip
import com.eona.app.designsystem.component.EonaDivider
import com.eona.app.designsystem.component.EonaIcon
import com.eona.app.designsystem.component.EonaListGroup
import com.eona.app.designsystem.component.EonaListRow
import com.eona.app.designsystem.component.EonaLoadingState
import com.eona.app.designsystem.component.EonaMessageState
import com.eona.app.designsystem.component.EonaSearchField
import com.eona.app.designsystem.component.EonaText
import com.eona.app.designsystem.foundation.EonaIcons
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.location.LocationRepository
import kotlinx.coroutines.delay
import java.util.Locale

/** What the next pick sets: the trip's destination, its start, or a saved place. */
private enum class PickTarget { Destination, Start, Home, Work }

@Composable
fun SearchRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val searchApi = remember { SearchApi() }
    val placesApi = remember { PlacesApi() }
    val recentsRepo = remember { RecentsRepository(context) }
    val savedRepo = remember { SavedPlacesRepository(context) }

    var recents by remember { mutableStateOf(recentsRepo.recents()) }
    val home by savedRepo.home.collectAsStateWithLifecycle()
    val work by savedRepo.work.collectAsStateWithLifecycle()
    val favorites by savedRepo.favorites.collectAsStateWithLifecycle()
    val start by ActiveTripRepository.start.collectAsStateWithLifecycle()
    val fix by LocationRepository.location.collectAsStateWithLifecycle()
    val settings by AppPreferences.settings.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(PickTarget.Destination) }
    var category by remember { mutableStateOf<PlaceCategory?>(null) }
    var categoryPlaces by remember { mutableStateOf<List<Place>>(emptyList()) }
    var categoryLoading by remember { mutableStateOf(false) }
    var categoryFailed by remember { mutableStateOf(false) }
    var categoryAttempt by remember { mutableStateOf(0) }

    // Live search, debounced: the backend merges places and addresses, near the driver.
    val latestFix by rememberUpdatedState(fix)
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < MIN_QUERY) {
            results = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        delay(DEBOUNCE_MS)
        results = searchApi.search(q, latestFix?.latitude, latestFix?.longitude, AccountRepository.token)
        loading = false
    }

    // Nearest places of the chosen category — searched from the trip's start point, else from
    // the driver's position (as soon as there is one).
    val hasFix = fix != null
    LaunchedEffect(category, start, hasFix, categoryAttempt) {
        val cat = category ?: return@LaunchedEffect
        categoryFailed = false
        val fromLat = start?.lat ?: fix?.latitude
        val fromLon = start?.lon ?: fix?.longitude
        if (fromLat == null || fromLon == null) {
            categoryPlaces = emptyList()
            categoryLoading = true
            return@LaunchedEffect
        }
        categoryLoading = true
        val found = placesApi.near(cat, fromLat, fromLon)
        categoryPlaces = found.orEmpty()
        categoryFailed = found == null
        categoryLoading = false
    }

    fun pick(place: Place) {
        when (target) {
            PickTarget.Destination -> {
                // "Suggestions de trajets" (Confidentialité): only then is the destination kept.
                if (settings.tripSuggestions) recentsRepo.add(place)
                ActiveTripRepository.setDestination(place)
                onBack()
            }
            PickTarget.Start -> {
                ActiveTripRepository.setStart(place)
                target = PickTarget.Destination
                query = ""
                category = null
            }
            PickTarget.Home -> {
                savedRepo.setHome(place)
                target = PickTarget.Destination
                query = ""
            }
            PickTarget.Work -> {
                savedRepo.setWork(place)
                target = PickTarget.Destination
                query = ""
            }
        }
    }

    SearchScreen(
        query = query,
        prompt = when (target) {
            PickTarget.Destination, PickTarget.Start -> "Où allez-vous ?"
            PickTarget.Home -> "Adresse de la maison"
            PickTarget.Work -> "Adresse du travail"
        },
        editingStart = target == PickTarget.Start,
        onEditArrival = { target = PickTarget.Destination; query = "" },
        onUseMyPosition = {
            ActiveTripRepository.setStart(null)
            target = PickTarget.Destination
            query = ""
        },
        start = start,
        home = home,
        work = work,
        favorites = favorites,
        recents = if (settings.tripSuggestions) recents else emptyList(),
        results = results,
        loading = loading,
        category = category,
        categoryPlaces = categoryPlaces,
        categoryLoading = categoryLoading,
        categoryFailed = categoryFailed,
        waitingForPosition = start == null && !hasFix,
        preferredFuel = settings.preferredFuel,
        fuelNearestOnly = settings.fuelNearestOnly,
        onFuelSelect = { fuel -> AppPreferences.updateSettings { it.copy(preferredFuel = fuel, fuelNearestOnly = false) } },
        onNearestOnly = { AppPreferences.updateSettings { it.copy(fuelNearestOnly = true) } },
        onQueryChange = { query = it },
        onPick = ::pick,
        onCategory = { cat ->
            // After a failed search, the same category again means "try again".
            if (category == cat && categoryFailed) categoryAttempt++ else category = if (category == cat) null else cat
            categoryPlaces = emptyList()
        },
        onEditStart = { target = PickTarget.Start; query = ""; category = null },
        onClearStart = { ActiveTripRepository.setStart(null) },
        onSetHome = { target = PickTarget.Home; query = "" },
        onSetWork = { target = PickTarget.Work; query = "" },
        onRemoveRecent = { id ->
            recentsRepo.remove(id)
            recents = recentsRepo.recents()
        },
        onToggleFavorite = { place -> savedRepo.toggleFavorite(FavoriteTrip(place, start)) },
        isFavorite = { id -> favorites.any { it.to.id == id || it.id == id } },
        onStartFavorite = { trip ->
            ActiveTripRepository.setStart(trip.from)
            ActiveTripRepository.setDestination(trip.to)
            onBack()
        },
        onBack = onBack,
    )
}

@Composable
fun SearchScreen(
    query: String,
    /** What the arrival asks for ("Où allez-vous ?", or a saved address being set). */
    prompt: String,
    start: Place?,
    /** The departure is being chosen: its line is the field. */
    editingStart: Boolean = false,
    onEditArrival: () -> Unit = {},
    onUseMyPosition: () -> Unit = {},
    home: Place?,
    work: Place?,
    favorites: List<FavoriteTrip>,
    recents: List<Place>,
    results: List<Place>,
    loading: Boolean,
    category: PlaceCategory?,
    categoryPlaces: List<Place>,
    categoryLoading: Boolean,
    /** The nearby search could not reach the backend. */
    categoryFailed: Boolean = false,
    /** No trip start and no GPS fix yet: nothing to search around. */
    waitingForPosition: Boolean = false,
    onQueryChange: (String) -> Unit,
    onPick: (Place) -> Unit,
    onCategory: (PlaceCategory) -> Unit,
    /** Fuel whose official price the "Carburant" results show. */
    preferredFuel: FuelType = FuelType.Gazole,
    /** "Proche uniquement": the nearest open stations, without prices. */
    fuelNearestOnly: Boolean = false,
    onFuelSelect: (FuelType) -> Unit = {},
    onNearestOnly: () -> Unit = {},
    onEditStart: () -> Unit,
    onClearStart: () -> Unit,
    onSetHome: () -> Unit,
    onSetWork: () -> Unit,
    onRemoveRecent: (String) -> Unit,
    onToggleFavorite: (Place) -> Unit,
    isFavorite: (String) -> Boolean,
    onStartFavorite: (FavoriteTrip) -> Unit,
    onBack: () -> Unit,
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing

    // Frosted glass over the HUD: Android has no Liquid Glass, and blurring the live map would
    // cost a TextureView (battery); a tinted pane lets the map and the HUD show through, light
    // or dark with the theme. It takes every touch, so nothing reaches the HUD under it.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas.copy(alpha = SEARCH_GLASS_ALPHA))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
    ) {
        // Departure and arrival, one above the other: the departure is always in sight, and a
        // tap on it is all it takes to change it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = spacing.lg, vertical = spacing.sm),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            RouteStopsCard(
                start = start,
                query = query,
                onQueryChange = onQueryChange,
                editingStart = editingStart,
                arrivalPrompt = prompt,
                onEditStart = onEditStart,
                onEditArrival = onEditArrival,
                onResetStart = onClearStart,
                modifier = Modifier.weight(1f),
            )
            Box(modifier = Modifier.height(STOP_LINE_HEIGHT), contentAlignment = Alignment.Center) {
                EonaText(
                    text = "Annuler",
                    style = EonaTheme.typography.label,
                    color = colors.accent,
                    modifier = Modifier
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() }
                        .padding(vertical = spacing.sm),
                )
            }
        }

        CategoryRow(selected = category, onSelect = onCategory)

        // Official prices ride on the stations the search already found. When none of
        // them has any (backend without prices yet), the list stays exactly as before.
        val showPrices = category == PlaceCategory.Fuel &&
            (categoryLoading || categoryPlaces.isEmpty() || categoryPlaces.any { it.fuel != null })
        if (showPrices) {
            FuelTypeRow(
                selected = preferredFuel,
                nearestOnly = fuelNearestOnly,
                onSelect = onFuelSelect,
                onNearestOnly = onNearestOnly,
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                query.trim().length >= MIN_QUERY && loading -> EonaLoadingState(label = "Recherche…")
                query.trim().length >= MIN_QUERY && results.isEmpty() -> EmptyResults(query)
                query.trim().length >= MIN_QUERY -> ResultList(results, onPick)
                category != null && categoryLoading -> EonaLoadingState(
                    label = if (waitingForPosition) "En attente de ta position…" else "Recherche autour de toi…",
                )
                category != null && categoryFailed -> EonaMessageState(
                    icon = EonaIcons.Warning,
                    title = "Recherche indisponible",
                    message = "Le serveur ne répond pas. Vérifie ta connexion, puis touche à nouveau « ${category.label} ».",
                )
                category != null && categoryPlaces.isEmpty() -> EonaMessageState(
                    icon = EonaIcons.Search,
                    title = "Rien trouvé",
                    message = "Aucun résultat pour « ${category.label} » dans les environs.",
                )
                category != null -> {
                    val nearestOnly = category == PlaceCategory.Fuel && fuelNearestOnly
                    val fuel = if (showPrices && !nearestOnly) preferredFuel else null
                    // Open places first, nearest first (in a city a station showing a price may
                    // go ahead); those closed right now follow, marked. "Proche uniquement": the
                    // open stations only, nearest first, no price.
                    val shown = remember(categoryPlaces, category, fuel, nearestOnly) {
                        val picked = NearbyPicker.pick(categoryPlaces, category, fuel, System.currentTimeMillis())
                        if (nearestOnly) NearbyResults(picked.open, emptyList()) else picked
                    }
                    NearbyList(results = shown, category = category, fuel = fuel, onPick = onPick)
                }
                else -> BlankState(
                    editingStart = editingStart,
                    onUseMyPosition = onUseMyPosition,
                    home = home,
                    work = work,
                    favorites = favorites,
                    recents = recents,
                    onPick = onPick,
                    onSetHome = onSetHome,
                    onSetWork = onSetWork,
                    onRemoveRecent = onRemoveRecent,
                    onToggleFavorite = onToggleFavorite,
                    isFavorite = isFavorite,
                    onStartFavorite = onStartFavorite,
                )
            }
        }
    }
}

/**
 * Departure over arrival, linked like a trip: a ring, three dots, a dot. The line being chosen is
 * the field; the other is one tap away. The departure always shows, "Ma position" by default.
 */
@Composable
private fun RouteStopsCard(
    start: Place?,
    query: String,
    onQueryChange: (String) -> Unit,
    editingStart: Boolean,
    arrivalPrompt: String,
    onEditStart: () -> Unit,
    onEditArrival: () -> Unit,
    onResetStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Row(
        modifier = modifier
            .clip(EonaTheme.shapes.lg)
            .background(colors.surface.copy(alpha = 0.45f))
            .border(0.5.dp, colors.separator, EonaTheme.shapes.lg)
            .padding(start = spacing.md, end = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        // The route's spine: departure ring, three dots, arrival dot.
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .border(2.5.dp, if (start != null || editingStart) colors.accent else colors.textSecondary, CircleShape),
            )
            repeat(3) { Box(Modifier.size(3.dp).clip(CircleShape).background(colors.textTertiary)) }
            Box(Modifier.size(12.dp).clip(CircleShape).background(colors.accent))
        }
        Column(modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.height(STOP_LINE_HEIGHT), contentAlignment = Alignment.CenterStart) {
                if (editingStart) {
                    StopField(query, onQueryChange, "Adresse de départ")
                } else {
                    StartLine(start = start, onEdit = onEditStart, onReset = onResetStart)
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
            Box(modifier = Modifier.height(STOP_LINE_HEIGHT), contentAlignment = Alignment.CenterStart) {
                if (editingStart) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onEditArrival),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        EonaText("Arrivée", style = EonaTheme.typography.caption, color = colors.textTertiary)
                        EonaText(arrivalPrompt, style = EonaTheme.typography.callout, color = colors.textSecondary, maxLines = 1)
                    }
                } else {
                    StopField(query, onQueryChange, arrivalPrompt)
                }
            }
        }
    }
}

/** "Départ · Ma position", with "Modifier" — and a cross to come back to the driver's position. */
@Composable
private fun StartLine(start: Place?, onEdit: () -> Unit, onReset: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onEdit),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            EonaText("Départ", style = EonaTheme.typography.caption, color = colors.textTertiary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                if (start == null) EonaIcon(EonaIcons.Gps, contentDescription = null, tint = colors.accent, size = 13.dp)
                AnimatedContent(
                    targetState = start?.name ?: "Ma position",
                    transitionSpec = {
                        (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                    },
                    label = "startName",
                ) { name ->
                    EonaText(
                        name,
                        style = EonaTheme.typography.callout,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (start != null) {
            // Back to the driver's own position, without typing anything.
            RowAction(EonaIcons.Close, "Repartir de ma position", onClick = onReset)
        }
        EonaText(
            "Modifier",
            style = EonaTheme.typography.caption,
            color = colors.accent,
            modifier = Modifier
                .clip(EonaTheme.shapes.pill)
                .background(colors.accent.copy(alpha = 0.14f))
                .clickable(onClick = onEdit)
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
        )
    }
}

/** The stop being typed; it takes the keyboard as it appears. */
@Composable
private fun StopField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val colors = EonaTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(placeholder) { runCatching { focus.requestFocus() } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.sm)) {
        EonaIcon(EonaIcons.Search, contentDescription = null, tint = colors.textTertiary, size = 18.dp)
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = EonaTheme.typography.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            if (value.isEmpty()) {
                EonaText(placeholder, style = EonaTheme.typography.body, color = colors.textTertiary, maxLines = 1)
            }
        }
        if (value.isNotEmpty()) RowAction(EonaIcons.Close, "Effacer") { onValueChange("") }
    }
}

/** The first line of the list while the departure is being chosen: the driver's own position. */
@Composable
private fun UseMyPositionRow(onClick: () -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(EonaTheme.shapes.md)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.sm, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            EonaIcon(EonaIcons.Gps, contentDescription = null, tint = colors.accent, size = 16.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            EonaText("Ma position", style = EonaTheme.typography.bodyStrong, color = colors.textPrimary)
            EonaText("Partir d'où je suis", style = EonaTheme.typography.footnote, color = colors.textSecondary)
        }
    }
}

/**
 * Category shortcuts, scrolled sideways: each one is its icon, drawn exactly as supplied,
 * inside its coloured square.
 */
@Composable
private fun CategoryRow(selected: PlaceCategory?, onSelect: (PlaceCategory) -> Unit) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        PlaceCategory.entries.forEach { category ->
            val on = category == selected
            val scale by animateFloatAsState(if (on) 1.08f else 1f, label = "chipScale")
            val square by animateColorAsState(
                if (on) colors.accent else categoryColor(category),
                label = "chipColor",
            )
            Column(
                modifier = Modifier
                    .width(76.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(EonaTheme.shapes.md)
                    .clickable { onSelect(category) }
                    .padding(vertical = spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(EonaTheme.shapes.md)
                        .background(square),
                    contentAlignment = Alignment.Center,
                ) {
                    // Untinted: the supplied colours stay as they are.
                    Image(
                        painter = painterResource(categoryIcon(category)),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
                EonaText(
                    category.label,
                    style = EonaTheme.typography.caption,
                    color = if (on) colors.accent else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The icon inside each category square (vectors and PNGs, shown as supplied). */
@DrawableRes
private fun categoryIcon(category: PlaceCategory): Int = when (category) {
    PlaceCategory.Fuel -> R.drawable.ic_place_fuel
    PlaceCategory.Charging -> R.drawable.ic_place_charging
    PlaceCategory.Parking -> R.drawable.ic_place_parking
    PlaceCategory.Tobacco -> R.drawable.ic_place_tobacco
    PlaceCategory.Garage -> R.drawable.ic_place_garage
    PlaceCategory.Hotel -> R.drawable.ic_place_hotel
    PlaceCategory.Atm -> R.drawable.ic_place_atm
}

/** One flat theme colour per category, behind its icon. */
@Composable
private fun categoryColor(category: PlaceCategory): Color = when (category) {
    PlaceCategory.Fuel -> EonaTheme.colors.radarMobile
    PlaceCategory.Charging -> EonaTheme.colors.success
    PlaceCategory.Parking -> EonaTheme.colors.info
    PlaceCategory.Tobacco -> EonaTheme.colors.danger
    PlaceCategory.Garage -> EonaTheme.colors.textSecondary
    PlaceCategory.Hotel -> EonaTheme.colors.controlZone
    PlaceCategory.Atm -> EonaTheme.colors.radarFixed
}

@Composable
private fun BlankState(
    editingStart: Boolean,
    onUseMyPosition: () -> Unit,
    home: Place?,
    work: Place?,
    favorites: List<FavoriteTrip>,
    recents: List<Place>,
    onPick: (Place) -> Unit,
    onSetHome: () -> Unit,
    onSetWork: () -> Unit,
    onRemoveRecent: (String) -> Unit,
    onToggleFavorite: (Place) -> Unit,
    isFavorite: (String) -> Boolean,
    onStartFavorite: (FavoriteTrip) -> Unit,
) {
    val spacing = EonaTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xl),
    ) {
        if (editingStart) UseMyPositionRow(onUseMyPosition)
        EonaListGroup(title = "Adresses") {
            SavedRow("Maison", EonaIcons.Home, home, onPick, onSetHome)
            EonaDivider(Modifier.padding(start = 58.dp))
            SavedRow("Travail", EonaIcons.Flag, work, onPick, onSetWork)
        }

        if (favorites.isNotEmpty()) {
            EonaListGroup(title = "Trajets favoris") {
                favorites.forEachIndexed { index, trip ->
                    EonaListRow(
                        title = trip.to.name,
                        subtitle = trip.from?.let { "Depuis ${it.name}" } ?: trip.to.subtitle,
                        leadingIcon = EonaIcons.Star,
                        leadingTint = EonaTheme.colors.accent,
                        onClick = { onStartFavorite(trip) },
                        trailing = {
                            RowAction(EonaIcons.Close, "Retirer des favoris") { onToggleFavorite(trip.to) }
                        },
                    )
                    if (index < favorites.lastIndex) EonaDivider(Modifier.padding(start = 58.dp))
                }
            }
        }

        if (recents.isNotEmpty()) {
            EonaListGroup(title = "Récents") {
                recents.forEachIndexed { index, place ->
                    EonaListRow(
                        title = place.name,
                        subtitle = place.subtitle,
                        leadingIcon = EonaIcons.History,
                        leadingTint = EonaTheme.colors.accent,
                        onClick = { onPick(place) },
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(EonaTheme.spacing.xs)) {
                                RowAction(
                                    icon = EonaIcons.Star,
                                    description = "Mettre en favori",
                                    tint = if (isFavorite(place.id)) EonaTheme.colors.accent else EonaTheme.colors.textTertiary,
                                ) { onToggleFavorite(place) }
                                RowAction(EonaIcons.Close, "Retirer des récents") { onRemoveRecent(place.id) }
                            }
                        },
                    )
                    if (index < recents.lastIndex) EonaDivider(Modifier.padding(start = 58.dp))
                }
            }
        }

        Spacer(Modifier.height(spacing.xxl))
    }
}

/**
 * Home / work. An empty one breathes gently and says "Définir" — otherwise nothing
 * suggests the row does anything at all.
 */
@Composable
private fun SavedRow(
    label: String,
    icon: ImageVector,
    place: Place?,
    onPick: (Place) -> Unit,
    onSet: () -> Unit,
) {
    val colors = EonaTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "savedScale")
    val breathing = rememberInfiniteTransition(label = "savedHint")
    val hint by breathing.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "savedHintAlpha",
    )

    Box(modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
        EonaListRow(
            title = label,
            subtitle = place?.subtitle?.ifBlank { null } ?: place?.name,
            leadingIcon = icon,
            leadingTint = colors.accent,
            interactionSource = interaction,
            onClick = { if (place != null) onPick(place) else onSet() },
            trailing = {
                if (place != null) {
                    RowAction(EonaIcons.Settings, "Changer l'adresse", onClick = onSet)
                } else {
                    EonaText(
                        "Définir",
                        style = EonaTheme.typography.caption,
                        color = colors.accent.copy(alpha = hint),
                        modifier = Modifier
                            .clip(EonaTheme.shapes.pill)
                            .background(colors.accent.copy(alpha = 0.12f * hint))
                            .padding(horizontal = EonaTheme.spacing.md, vertical = 4.dp),
                    )
                }
            },
        )
    }
}

@Composable
private fun RowAction(
    icon: ImageVector,
    description: String,
    tint: Color = EonaTheme.colors.textTertiary,
    onClick: () -> Unit,
) {
    EonaIcon(
        icon,
        contentDescription = description,
        tint = tint,
        size = 18.dp,
        modifier = Modifier
            .clip(EonaTheme.shapes.pill)
            .clickable(onClick = onClick)
            .padding(6.dp),
    )
}

/** Addresses found by the text search, in the order they came. */
@Composable
private fun ResultList(results: List<Place>, onPick: (Place) -> Unit) {
    val spacing = EonaTheme.spacing
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
            EonaListRow(
                title = place.name,
                subtitle = place.subtitle,
                leadingIcon = place.kind.icon(),
                leadingTint = EonaTheme.colors.accent,
                onClick = { onPick(place) },
            )
            EonaDivider(Modifier.padding(start = 58.dp))
        }
    }
}

/**
 * The nearby places, as [NearbyPicker] ranked them: open ones first — with [fuel] set, those
 * showing a price for it, then "Sans prix récent" — and those closed right now under their
 * own heading, dimmed. The sources are credited at the bottom.
 */
@Composable
private fun NearbyList(results: NearbyResults, category: PlaceCategory, fuel: FuelType?, onPick: (Place) -> Unit) {
    val spacing = EonaTheme.spacing
    val now = remember(results, fuel) { System.currentTimeMillis() }
    // A stable split of a list already sorted by distance: nothing moves inside a group.
    val (priced, unpriced) = remember(results, fuel, now) {
        if (fuel == null) results.open to emptyList() else results.open.partition { it.showsFuelPrice(fuel, now) }
    }
    // Another fuel or category is another list: start it from the top. (A lazy list otherwise
    // keeps the first visible station in view, wherever it moved to.)
    val listState = rememberLazyListState()
    LaunchedEffect(category, fuel) { listState.scrollToItem(0) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = spacing.md,
            end = spacing.lg,
            top = spacing.xs,
            bottom = spacing.xxxl,
        ),
    ) {
        items(items = priced, key = { it.id }) { place ->
            NearbyRow(place, category, fuel, now, closed = false, onPick = onPick)
        }
        if (unpriced.isNotEmpty()) {
            if (priced.isNotEmpty()) item(key = "fuel-no-recent-price") { SectionLabel("Sans prix récent") }
            items(items = unpriced, key = { it.id }) { place ->
                NearbyRow(place, category, fuel, now, closed = false, onPick = onPick)
            }
        }
        if (results.closed.isNotEmpty()) {
            item(key = "closed-now") { SectionLabel("Fermés en ce moment") }
            items(items = results.closed, key = { it.id }) { place ->
                NearbyRow(place, category, fuel, now, closed = true, onPick = onPick)
            }
        }
        item(key = "nearby-sources") {
            EonaText(
                if (fuel != null) {
                    "Prix officiels : prix-carburants.gouv.fr. Seuls les prix mis à jour depuis moins de 96 h sont affichés. " +
                        "Lieux et horaires : © contributeurs OpenStreetMap."
                } else {
                    "Lieux et horaires : © contributeurs OpenStreetMap."
                },
                style = EonaTheme.typography.footnote,
                color = EonaTheme.colors.textTertiary,
                modifier = Modifier.padding(start = spacing.sm, top = spacing.md),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    EonaText(
        text,
        style = EonaTheme.typography.caption,
        color = EonaTheme.colors.textTertiary,
        modifier = Modifier.padding(start = EonaTheme.spacing.sm, top = EonaTheme.spacing.lg, bottom = EonaTheme.spacing.xs),
    )
}

/**
 * One nearby place: its category square, its name, how far and where, whether it is open
 * (with today's hours or when it opens), and what matters for its kind — power and connectors,
 * fee and size, brand, stars. The official price sits on the right for fuel.
 */
@Composable
private fun NearbyRow(
    place: Place,
    category: PlaceCategory,
    fuel: FuelType?,
    nowMillis: Long,
    closed: Boolean,
    onPick: (Place) -> Unit,
) {
    val colors = EonaTheme.colors
    val spacing = EonaTheme.spacing
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        if (pressed) colors.surfaceHigh.copy(alpha = 0.5f) else Color.Transparent,
        label = "nearbyRowBackground",
    )
    val status = remember(place, nowMillis) { NearbyLabels.status(place.nearby?.hours, nowMillis) }
    val details = remember(place) { NearbyLabels.details(place) }
    val where = remember(place) {
        listOfNotNull(place.distanceMeters?.let(NearbyLabels::distance), place.subtitle.ifBlank { null }).joinToString(" · ")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (closed) 0.6f else 1f }
            .clip(EonaTheme.shapes.md)
            .background(background)
            .clickable(interactionSource = interaction, indication = null) { onPick(place) }
            .padding(horizontal = spacing.sm, vertical = spacing.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(EonaTheme.shapes.md)
                .background(categoryColor(category)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(categoryIcon(category)),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EonaText(
                place.name,
                style = EonaTheme.typography.bodyStrong,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (where.isNotEmpty()) {
                EonaText(
                    where,
                    style = EonaTheme.typography.footnote,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (status != null) StatusLine(status)
            if (details.isNotEmpty()) {
                EonaText(
                    details.joinToString(" · "),
                    style = EonaTheme.typography.footnote,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (fuel != null) FuelPriceTag(place = place, fuel = fuel, nowMillis = nowMillis)
    }
    EonaDivider(Modifier.padding(start = 40.dp + spacing.md + spacing.sm))
}

/** "● Ouvert  07:00–21:00", "● Fermé  ouvre demain à 07:00" — the dot and word in the state's colour. */
@Composable
private fun StatusLine(status: NearbyLabels.Status) {
    val colors = EonaTheme.colors
    val tint = when (status.tone) {
        NearbyLabels.Tone.Positive -> colors.success
        NearbyLabels.Tone.Warning -> colors.warning
        NearbyLabels.Tone.Negative -> colors.danger
        NearbyLabels.Tone.Neutral -> colors.textSecondary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(tint))
        EonaText(
            status.text,
            style = EonaTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
        )
        status.detail?.let {
            EonaText(
                it,
                style = EonaTheme.typography.footnote,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Which fuel's price the stations show, or "Proche uniquement" (the nearest open stations, no
 * price); the choice is remembered.
 */
@Composable
private fun FuelTypeRow(
    selected: FuelType,
    nearestOnly: Boolean,
    onSelect: (FuelType) -> Unit,
    onNearestOnly: () -> Unit,
) {
    val spacing = EonaTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        EonaChip(label = "Proche uniquement", selected = nearestOnly, onClick = onNearestOnly)
        FuelType.entries.forEach { fuel ->
            EonaChip(label = fuel.label, selected = !nearestOnly && fuel == selected, onClick = { onSelect(fuel) })
        }
    }
}

/**
 * The official price of [fuel] at this station: "2,283 €" over its age, "Rupture" when the
 * station is out of that fuel, "—" when it has no price younger than 96 h (or no match).
 */
@Composable
private fun FuelPriceTag(place: Place, fuel: FuelType, nowMillis: Long) {
    val colors = EonaTheme.colors
    val price = place.fuel?.prices?.firstOrNull { it.type == fuel }
    Column(horizontalAlignment = Alignment.End) {
        when {
            price?.outOfStock == true -> EonaText("Rupture", style = EonaTheme.typography.callout, color = colors.hazard)
            price != null && price.isFresh(nowMillis) -> {
                EonaText(
                    "%.3f €".format(Locale.FRANCE, price.euros),
                    style = EonaTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                )
                priceAge(price, nowMillis)?.let {
                    EonaText(it, style = EonaTheme.typography.caption, color = colors.textTertiary)
                }
            }
            else -> EonaText("—", style = EonaTheme.typography.callout, color = colors.textTertiary)
        }
    }
}

/** "il y a 12 min", "il y a 3 h", "il y a 1 j" — how old a shown price is. */
private fun priceAge(price: FuelPrice, nowMillis: Long): String? {
    val at = price.updatedAtMillis ?: return null
    val minutes = ((nowMillis - at) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        minutes < 24 * 60 -> "il y a ${minutes / 60} h"
        else -> "il y a ${minutes / (24 * 60)} j"
    }
}

@Composable
private fun EmptyResults(query: String) {
    EonaMessageState(
        icon = EonaIcons.Search,
        title = "Aucun résultat",
        message = "Rien ne correspond à « $query ».",
    )
}

private fun PlaceKind.icon(): ImageVector = when (this) {
    PlaceKind.Home -> EonaIcons.Home
    PlaceKind.Work -> EonaIcons.Flag
    PlaceKind.Favorite -> EonaIcons.Star
    PlaceKind.Recent -> EonaIcons.History
    PlaceKind.Result -> EonaIcons.MapPin
}

private const val MIN_QUERY = 3
/** The frosted pane over the HUD: enough to read on, the map still showing through. */
private const val SEARCH_GLASS_ALPHA = 0.8f
/** Short: the suggestions follow the typing without flooding the server. */
private const val DEBOUNCE_MS = 200L
/** Each line of the departure/arrival card. */
private val STOP_LINE_HEIGHT = 46.dp

@Preview(name = "Recherche", showBackground = true, backgroundColor = 0xFF06070A, widthDp = 380, heightDp = 800)
@Composable
private fun SearchScreenPreview() {
    EonaTheme(darkTheme = true) {
        SearchScreen(
            query = "",
            prompt = "Où allez-vous ?",
            start = null,
            home = null,
            work = null,
            favorites = emptyList(),
            recents = listOf(Place("p", "Paris", "Île-de-France", PlaceKind.Recent, 48.85, 2.35)),
            results = emptyList(),
            loading = false,
            category = null,
            categoryPlaces = emptyList(),
            categoryLoading = false,
            onQueryChange = {},
            onPick = {},
            onCategory = {},
            onEditStart = {},
            onClearStart = {},
            onSetHome = {},
            onSetWork = {},
            onRemoveRecent = {},
            onToggleFavorite = {},
            isFavorite = { false },
            onStartFavorite = {},
            onBack = {},
        )
    }
}
