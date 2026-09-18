package com.xradar.app.feature.drive

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xradar.app.core.drive.AlertBeeps
import com.xradar.app.core.drive.TripRecorder
import com.xradar.app.core.geo.Geo
import com.xradar.app.core.geo.GuidanceSides
import com.xradar.app.core.geo.GuidanceText
import com.xradar.app.core.geo.RoutePath
import com.xradar.app.core.model.GeoPoint
import com.xradar.app.core.model.GpsSignal
import com.xradar.app.core.model.GuidanceInstruction
import com.xradar.app.core.model.LocationSample
import com.xradar.app.core.model.Place
import com.xradar.app.core.model.Radar
import com.xradar.app.core.model.ReportRelevance
import com.xradar.app.core.model.RadarZone
import com.xradar.app.core.model.ReportType
import com.xradar.app.core.model.RoadAlert
import com.xradar.app.core.model.Route
import com.xradar.app.core.model.RouteStep
import com.xradar.app.core.model.SignType
import com.xradar.app.core.model.SpeedLimitChange
import com.xradar.app.core.model.SpeedLimitSource
import com.xradar.app.core.model.TripInfo
import com.xradar.app.core.model.TripRecord
import com.xradar.app.core.model.UserReport
import com.xradar.app.core.model.isEnforcement
import com.xradar.app.data.account.AccountRepository
import com.xradar.app.data.preferences.AlertPreferences
import com.xradar.app.data.preferences.AppPreferences
import com.xradar.app.data.preferences.OverspeedWarning
import com.xradar.app.data.radar.RadarRepository
import com.xradar.app.data.reports.NewReport
import com.xradar.app.data.reports.ReportsRepository
import com.xradar.app.data.routing.ActiveTripRepository
import com.xradar.app.data.routing.RouteAnswer
import com.xradar.app.data.routing.RoutingRepository
import com.xradar.app.data.account.AccessDeniedException
import com.xradar.app.feature.subscription.OffersPrompt
import com.xradar.app.feature.subscription.PaywallReason
import com.xradar.app.data.speedlimits.NewSpeedLimitReport
import com.xradar.app.data.speedlimits.SpeedLimitRepository
import com.xradar.app.data.stats.TripHistoryRepository
import com.xradar.app.feature.drive.component.key
import com.xradar.app.location.LocationRepository
import com.xradar.app.media.MediaRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Feeds the HUD from real data: real GPS (speed/position/signal), real fixed
 * radars ([RadarRepository]) for alerts + active limit, and a real route
 * ([RoutingRepository] → OSRM via the backend) when a destination is chosen in
 * search ([ActiveTripRepository]). [DriveScreen] consumes one [DriveUiState].
 */
class DriveViewModel(application: Application) : AndroidViewModel(application) {

    private val radarRepository = RadarRepository()
    private val routingRepository = RoutingRepository()
    private val reportsRepository = ReportsRepository()
    private val speedLimitRepository = SpeedLimitRepository()
    private val liveApi = com.xradar.app.data.live.LiveApi()
    private val tripHistory = TripHistoryRepository(application)
    private val radars = MutableStateFlow<List<Radar>>(emptyList())
    private val reports = MutableStateFlow<List<UserReport>>(emptyList())
    private val zones = MutableStateFlow<List<RadarZone>>(emptyList())
    private val signApi = com.xradar.app.data.signs.SignApi()
    private val signs = MutableStateFlow<List<com.xradar.app.core.model.RoadSign>>(emptyList())
    private val guidance = MutableStateFlow<GuidanceInstruction?>(null)
    /** Speed limit where the driver is, from the road's own limit (null = unknown). */
    private val osmLimit = MutableStateFlow<Int?>(null)
    /** Limit changes along the active route: (distance along it in metres, km/h), in order. */
    @Volatile private var routeLimits: List<Pair<Double, Int>> = emptyList()
    @Volatile private var routeLimitPath: RoutePath? = null
    /** True while the limit is read from the route the driver follows (no polling then). */
    @Volatile private var limitFromRoute = false
    /** Set when a destination was chosen but routing came back empty. */
    private val routeError = MutableStateFlow(false)
    private val speaker = GuidanceSpeaker(application)
    private val sounds = AlertSoundPlayer(application)
    // Alert sounds: the alerts already announced by a sound, and those past their laser burst.
    private val soundedAlerts = HashSet<String>()
    private val burstAlerts = HashSet<String>()
    /** Music in the three supported apps. Only [uiState] reads it — see there. */
    private val media = MediaRepository.run {
        init(application)
        state
    }
    /** Whether the music banner is open: HUD state only, never persisted. */
    private val musicOpen = MutableStateFlow(false)

    /** Radars + reports + radar-car zones, pre-combined so the main combine stays ≤5 flows. */
    private val roadObjects = combine(radars, reports, zones) { r, rep, z -> Triple(r, rep, z) }

    private var lastFetchLat = Double.NaN
    private var lastFetchLon = Double.NaN
    // Radars: the route they were loaded along (null = the ring around the driver),
    // whether that worked, and where the ring was last centred.
    private var radarsRoute: Route? = null
    private var radarsOnRoute = false
    private var ringLat = Double.NaN
    private var ringLon = Double.NaN
    private var ringRetryAt = 0L
    private var lastRecalcAt = 0L
    private var overspeeding = false

    // Active route as a measurable polyline + each maneuver's distance along it.
    private var path: RoutePath? = null
    private var stepAlong: DoubleArray = DoubleArray(0)
    /** The route's steps, each turn's side checked against the road's own bend. */
    private var guidanceSteps: List<RouteStep> = emptyList()
    // The route thinned to one point every couple of km, plus its padded bounding box:
    // enough to ask "is this alert on my trip?" thousands of times without cost.
    private var corridor: List<GeoPoint> = emptyList()
    private var corridorMinLat = 0.0
    private var corridorMaxLat = 0.0
    private var corridorMinLon = 0.0
    private var corridorMaxLon = 0.0

    // Turn-by-turn cursor over the active route's steps.
    private var stepIndex = 1
    private var announcedFar = false
    private var announcedNear = false

    // Live accumulation of the trip in progress (saved locally when it ends).
    private var trip: TripRecorder? = null

    /**
     * The driving state. The voice and trip collectors below keep it running for as long as
     * the ViewModel lives; the screen reads it through [uiState].
     */
    private val driveState: StateFlow<DriveUiState> = combine(
        LocationRepository.location,
        LocationRepository.signal,
        roadObjects,
        ActiveTripRepository.route,
        AppPreferences.alerts,
    ) { sample, signal, objects, route, prefs ->
        val (radarList, reportList, zoneList) = objects
        // Only keep radar/report types the user enabled in the Options (E1) menu.
        // Trial over: the map stays, the radars and alerts do not.
        val restricted = AccountRepository.account.value?.isRestricted == true
        val enabledRadars = if (restricted) emptyList() else radarList.filter {
            (it.isSpeedRadar && prefs.radarFixed) || (!it.isSpeedRadar && prefs.shows(ReportType.Camera))
        }
        // Everything the backend still serves is alive (it prunes under the minimum
        // score), so the only filter left here is what the driver asked to see.
        val enabledReports = if (restricted) emptyList() else reportList.filter { prefs.shows(it.type) }
        // While navigating, keep what is on the trip: within 15 km of the route itself
        // (so the whole itinerary stays visible when you zoom out) or of the driver.
        val here = sample
        val onTrip = route != null && here != null
        val shownRadars = if (onTrip) enabledRadars.filter { onTripRoute(it.lat, it.lon, here!!) } else enabledRadars
        val shownReports = if (onTrip) enabledReports.filter { onTripRoute(it.lat, it.lon, here!!) } else enabledReports
        val speedKmh = when (signal) {
            GpsSignal.Searching, GpsSignal.Lost -> 0
            else -> (sample?.speedKmh ?: 0f).roundToInt().coerceAtLeast(0)
        }
        val (radarAlerts, limit) = relevantAheadOf(shownRadars, sample, speedKmh)
        // A traffic jam stays on the map but is no alert: the route can avoid it instead.
        val reportAlerts = reportsAhead(shownReports.filter { it.type.raisesAlerts }, sample, speedKmh)
        // Every alert stays: the HUD stacks them. The nearest one still drives the voice
        // and the trip's alert count, exactly as before.
        val alerts = (radarAlerts + reportAlerts).sortedBy { it.distanceMeters }
        DriveUiState(
            speedKmh = speedKmh,
            speedLimitKmh = limit,
            speedLimitSource = if (limit != null) SpeedLimitSource.Radar else null,
            trip = tripFrom(route),
            alert = alerts.firstOrNull(),
            gpsSignal = signal,
            alerts = alerts,
            location = sample,
            radars = shownRadars,
            reports = shownReports,
            zones = zoneList,
            routePoints = route?.points ?: emptyList(),
        )
    }.combine(guidance) { state, instruction ->
        state.copy(guidance = instruction)
    }.combine(signs) { state, s ->
        state.copy(signs = s)
    }.combine(routeError) { state, failed ->
        state.copy(routeError = failed)
    }.combine(osmLimit) { state, live ->
        // The road's own limit beats the radar VMA: it is true everywhere, all the time.
        if (live != null) state.copy(speedLimitKmh = live, speedLimitSource = SpeedLimitSource.Road) else state
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DriveUiState(0, null, null, null, GpsSignal.Searching),
    )

    /**
     * What the HUD renders: [driveState] plus the music banner. Only the driving screen
     * collects it, so the media sessions are watched while that screen is started (and
     * [STOP_TIMEOUT_MS] after), never by the collectors that keep [driveState] running.
     */
    val uiState: StateFlow<DriveUiState> = combine(driveState, media, musicOpen) { state, playback, open ->
        state.copy(media = playback, musicOpen = open)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DriveUiState(0, null, null, null, GpsSignal.Searching),
    )

    init {
        // Refetch reports around the driver as they move.
        viewModelScope.launch {
            LocationRepository.location.collect { sample ->
                if (sample == null) return@collect
                if (shouldFetch(sample)) {
                    lastFetchLat = sample.latitude
                    lastFetchLon = sample.longitude
                    refreshReports(sample.latitude, sample.longitude)
                }

            }
        }
        // Radars (speed + red-light cameras) follow the road signs: the trip's radars
        // while navigating, only a ring around the driver otherwise.
        viewModelScope.launch {
            combine(ActiveTripRepository.route, LocationRepository.location) { route, fix -> route to fix }
                .collect { (route, fix) -> refreshRadars(route, fix) }
        }
        // Time and distance on the road with the app — trip or not — synced each minute.
        viewModelScope.launch {
            var lastLat = Double.NaN
            var lastLon = Double.NaN
            var lastAt = 0L
            var pendingS = 0.0
            var pendingM = 0.0
            var lastFlush = System.currentTimeMillis()
            LocationRepository.location.collect { fix ->
                if (fix == null) return@collect
                val now = System.currentTimeMillis()
                val moving = (fix.speedMps ?: 0f) > DRIVE_MIN_SPEED_MS
                if (!lastLat.isNaN() && moving) {
                    val step = Geo.haversine(lastLat, lastLon, fix.latitude, fix.longitude)
                    val dt = (now - lastAt) / 1000.0
                    if (step in TRIP_MIN_STEP_M..TRIP_MAX_STEP_M && dt in 0.0..DRIVE_MAX_GAP_S) {
                        pendingM += step
                        pendingS += dt
                    }
                }
                lastLat = fix.latitude
                lastLon = fix.longitude
                lastAt = now
                if (now - lastFlush >= DRIVE_FLUSH_MS && pendingS >= 1.0) {
                    val s = pendingS.toInt()
                    val m = pendingM.toInt()
                    if (AccountRepository.postDrive(s, m)) {
                        pendingS -= s
                        pendingM -= m
                    }
                    lastFlush = now
                }
            }
        }
        // Reports are time-sensitive: refresh them on a short interval too.
        viewModelScope.launch {
            while (true) {
                LocationRepository.location.value?.let { fix -> refreshReports(fix.latitude, fix.longitude) }
                delay(REPORT_REFRESH_MS)
            }
        }
        // Presence: the app says it is open, and whether a trip runs. Counted by the backend,
        // shown to nobody, no position sent.
        viewModelScope.launch {
            while (true) {
                AccountRepository.token?.let { token ->
                    liveApi.presence(token, inTrip = ActiveTripRepository.destination.value != null)
                }
                delay(PRESENCE_MS)
            }
        }
        // On the route, the limit is read from the route's own limit changes at the driver's
        // progress: instant, and no request. Off it (or with no route), the polling below runs.
        viewModelScope.launch {
            LocationRepository.location.collect { fix ->
                val rp = routeLimitPath
                val changes = routeLimits
                val match = if (fix == null || rp == null || changes.isEmpty()) null else rp.match(fix.latitude, fix.longitude)
                if (match == null || match.offRouteMeters > ROUTE_LIMIT_MAX_OFF_M) {
                    limitFromRoute = false
                    return@collect
                }
                limitFromRoute = true
                osmLimit.value = (changes.lastOrNull { it.first <= match.alongMeters } ?: changes.first()).second
            }
        }
        // Live speed limit under the car, refreshed as we move: the backend follows the road
        // the driver is on (course, and the road id it gave last time).
        viewModelScope.launch {
            var lastLat = Double.NaN
            var lastLon = Double.NaN
            var lastHitAt = 0L
            var lastWayId: String? = null
            while (true) {
                val fix = LocationRepository.location.value
                val moved = fix != null &&
                    (lastLat.isNaN() || Geo.haversine(lastLat, lastLon, fix.latitude, fix.longitude) > LIMIT_MOVE_M)
                if (fix != null && moved && !limitFromRoute) {
                    lastLat = fix.latitude
                    lastLon = fix.longitude
                    val result = signApi.limit(fix.latitude, fix.longitude, fix.bearingDeg?.toDouble(), lastWayId)
                    lastWayId = result?.wayId ?: lastWayId
                    val v = result?.kmh
                    val now = System.currentTimeMillis()
                    if (result == null) {
                        // No answer: the sign shown stays, and the next poll asks again.
                        lastLat = Double.NaN
                    } else if (v != null) {
                        osmLimit.value = v
                        lastHitAt = now
                    } else if (now - lastHitAt > LIMIT_STALE_MS) {
                        // Nothing mapped here for a while — stop showing a stale sign.
                        osmLimit.value = null
                    }
                }
                delay(LIMIT_POLL_MS)
            }
        }
        // Toll / motorway preferences: recompute the live route as soon as they change.
        viewModelScope.launch {
            AppPreferences.settings
                .map { Triple(it.avoidTolls, it.avoidHighways, it.avoidTraffic) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    val destination = ActiveTripRepository.destination.value ?: return@collect
                    val fix = LocationRepository.location.value ?: return@collect
                    routingRepository.route(
                        GeoPoint(fix.latitude, fix.longitude),
                        GeoPoint(destination.lat, destination.lon),
                        avoidOptions(),
                    ).routeOrNull?.let { ActiveTripRepository.setRoute(it) }
                }
        }
        // Compute the route whenever a destination is chosen; track the trip session.
        viewModelScope.launch {
            ActiveTripRepository.destination.collect { destination ->
                if (destination == null) {
                    finalizeTrip()
                    ActiveTripRepository.setRoute(null)
                    return@collect
                }
                startTrip(destination)
                routeError.value = false
                // A simulated departure wins over the GPS: that is the point of it.
                val simulated = ActiveTripRepository.start.value
                val fix = LocationRepository.location.value
                val from = simulated?.let { GeoPoint(it.lat, it.lon) }
                    ?: fix?.let { GeoPoint(it.latitude, it.longitude) }
                    ?: return@collect
                // Silent retries: a connection dropping for a few seconds should not kill the trip.
                var answer = routingRepository.route(
                    from,
                    GeoPoint(destination.lat, destination.lon),
                    avoidOptions(),
                )
                for (wait in ROUTE_RETRY_MS) {
                    if (answer !is RouteAnswer.Failed) break
                    delay(wait)
                    if (ActiveTripRepository.destination.value != destination) break
                    val again = if (simulated != null) from else {
                        LocationRepository.location.value
                            ?.let { GeoPoint(it.latitude, it.longitude) } ?: from
                    }
                    answer = routingRepository.route(
                        again,
                        GeoPoint(destination.lat, destination.lon),
                        avoidOptions(),
                    )
                }
                if (ActiveTripRepository.destination.value != destination) return@collect
                if (answer is RouteAnswer.Denied) {
                    // Trial over, or today's trips used: no trip, the offers show.
                    routeError.value = false
                    ActiveTripRepository.clear()
                    OffersPrompt.show(PaywallReason.of(answer.denial))
                    viewModelScope.launch { AccountRepository.reload() }
                    return@collect
                }
                val route = answer.routeOrNull
                routeError.value = route == null
                ActiveTripRepository.setRoute(route)
                // The trip's estimate, for "temps réel vs temps prévu".
                if (route != null) trip?.plan(route)
                // A guest's count of the day moved on.
                if (route != null && AccountRepository.account.value?.limits != null) {
                    viewModelScope.launch { AccountRepository.reload() }
                }
            }
        }
        // Distance, speed and stops while a trip is active, and its arrival.
        viewModelScope.launch {
            LocationRepository.location.collect { sample ->
                val current = trip ?: return@collect
                if (sample == null) return@collect
                current.add(sample)
                // Auto-finish when we reach the destination.
                ActiveTripRepository.destination.value?.let { dest ->
                    val toDest = Geo.haversine(sample.latitude, sample.longitude, dest.lat, dest.lon)
                    if (toDest < ARRIVE_M && current.distanceMeters >= TripRecorder.MIN_METERS) ActiveTripRepository.clear()
                }
            }
        }
        // The alerts the trip reaches, each once, for its history.
        viewModelScope.launch {
            driveState.collect { s -> trip?.meet(s.alerts) }
        }
        // Sounds as alerts show up; voice announcements for radars/reports (distance steps) + overspeed.
        viewModelScope.launch {
            driveState.collect { s ->
                val prefs = AppPreferences.alerts.value
                if (prefs.sound) soundNewAlerts(s.alerts, prefs.vibration)
                if (prefs.voice) announceAlert(s.alert)
                warnOverspeed(s.speedKmh, s.speedLimitKmh, prefs)
            }
        }
        // Radarbot's approach: beeps faster and faster toward the nearest speed enforcement
        // ahead, then the laser burst at it. Quiet while the voice speaks or the car waits.
        viewModelScope.launch {
            var lastBeepAt = 0L
            while (true) {
                delay(BEEP_TICK_MS)
                val prefs = AppPreferences.alerts.value
                val s = driveState.value
                val nearest = s.alert ?: continue
                if (!prefs.sound || speaker.isSpeaking || !nearest.type.isEnforcement) continue
                if (s.speedKmh < AlertBeeps.MIN_SPEED_KMH) continue
                if (nearest.distanceMeters <= AlertBeeps.BURST_METERS) {
                    if (burstAlerts.add(nearest.key)) sounds.play(AlertSound.Laser, prefs.vibration)
                    continue
                }
                val interval = AlertBeeps.intervalMs(nearest.distanceMeters) ?: continue
                val now = SystemClock.elapsedRealtime()
                if (now - lastBeepAt < interval) continue
                lastBeepAt = now
                sounds.play(AlertSound.Beep, prefs.vibration)
            }
        }
        // Recompute the route if the driver leaves it (off-route detection).
        viewModelScope.launch {
            LocationRepository.location.collect { sample ->
                val destination = ActiveTripRepository.destination.value ?: return@collect
                val route = ActiveTripRepository.route.value ?: return@collect
                if (sample == null) return@collect
                // A simulated trip is not being driven: never "correct" it.
                if (ActiveTripRepository.start.value != null) return@collect
                val offBy = route.points.minOfOrNull {
                    Geo.haversine(sample.latitude, sample.longitude, it.lat, it.lon)
                } ?: return@collect
                val now = System.currentTimeMillis()
                if (offBy > OFF_ROUTE_M && now - lastRecalcAt > RECALC_COOLDOWN_MS) {
                    lastRecalcAt = now
                    routingRepository.route(
                        GeoPoint(sample.latitude, sample.longitude),
                        GeoPoint(destination.lat, destination.lon),
                        avoidOptions(),
                    ).routeOrNull?.let { ActiveTripRepository.setRoute(it) }
                }
            }
        }
        // Road signs belong to the trip: the whole route at once, nothing at all when simply
        // driving around.
        viewModelScope.launch {
            ActiveTripRepository.route.collectLatest { route -> loadRouteSigns(route) }
        }
        // Reset the turn-by-turn cursor whenever the route changes (new trip or recalc).
        viewModelScope.launch {
            ActiveTripRepository.route.collect { route ->
                stepIndex = 1
                announcedFar = false
                announcedNear = false
                path = route?.points?.takeIf { it.size >= 2 }?.let { RoutePath(it) }
                stepAlong = buildStepAlong(path, route)
                guidanceSteps = GuidanceSides.checked(route?.steps.orEmpty(), stepAlong, path)
                buildCorridor(route)
                if (route == null || route.steps.size < 2) {
                    guidance.value = null
                    speaker.stop()
                }
            }
        }
        // Produce the next instruction (and speak it) as the driver advances.
        viewModelScope.launch {
            LocationRepository.location.collect { sample ->
                updateGuidance(sample, ActiveTripRepository.route.value)
            }
        }
    }

    /**
     * Advance the step cursor to the maneuver ahead, emit it, and voice the cues.
     * Distances follow the road (map-matched along the route), never the crow-flight
     * line, and every spoken number is a real marker crossed at the moment it is said.
     */
    private fun updateGuidance(sample: LocationSample?, route: Route?) {
        val raw = route?.steps ?: emptyList()
        val steps = if (guidanceSteps.size == raw.size) guidanceSteps else raw
        if (sample == null || steps.size < 2) {
            guidance.value = null
            return
        }
        if (stepIndex >= steps.size) stepIndex = steps.size - 1

        val rp = path
        val onRoute = stepAlong.size == steps.size
        val driverAlong = if (!onRoute) null else rp?.match(sample.latitude, sample.longitude)
            ?.takeIf { it.offRouteMeters <= ON_ROUTE_M }?.alongMeters

        if (driverAlong != null) {
            // We know exactly how far along the road we are: a maneuver is behind us once we are
            // STEP_PASSED_M past its point, so the banner keeps the turn being made until it is
            // done instead of already showing the next one.
            while (stepIndex < steps.size - 1 && driverAlong >= stepAlong[stepIndex] + STEP_PASSED_M) {
                stepIndex++
                announcedFar = false
                announcedNear = false
            }
        } else {
            while (stepIndex < steps.size - 1) {
                val cur = steps[stepIndex]
                val curDist = Geo.haversine(sample.latitude, sample.longitude, cur.location.lat, cur.location.lon)
                val next = steps[stepIndex + 1]
                val nextDist = Geo.haversine(sample.latitude, sample.longitude, next.location.lat, next.location.lon)
                if (curDist < STEP_PASSED_M || nextDist < curDist) {
                    stepIndex++
                    announcedFar = false
                    announcedNear = false
                } else {
                    break
                }
            }
        }

        val target = steps[stepIndex]
        val meters = if (driverAlong != null) {
            (stepAlong[stepIndex] - driverAlong).coerceAtLeast(0.0).roundToInt()
        } else {
            Geo.haversine(
                sample.latitude, sample.longitude, target.location.lat, target.location.lon,
            ).roundToInt()
        }

        guidance.value = GuidanceInstruction(
            maneuver = GuidanceText.maneuverOf(target),
            distanceMeters = meters,
            primaryText = GuidanceText.verb(target),
            roadName = target.name.ifBlank { null },
        )

        if (!AppPreferences.alerts.value.voice) return
        val speedMs = (sample.speedMps ?: 0f).toDouble().coerceAtLeast(0.0)
        val secondsAway = if (speedMs > 1.0) meters / speedMs else Double.MAX_VALUE

        // "Maintenant" fires on time (distance at low speed, seconds at high speed).
        if (!announcedNear && (meters <= NEAR_ANNOUNCE_M || secondsAway <= NEAR_ANNOUNCE_S)) {
            announcedNear = true
            announcedFar = true // never announce a distance after the final cue
            speaker.speak(GuidanceText.spokenNear(target))
            return
        }
        if (announcedFar) return
        // Heads-up: speak a round marker exactly as we cross it, started early enough
        // that the driver is at that distance when they hear the number (not before).
        val lead = speedMs * SPEECH_LEAD_S
        val horizon = (speedMs * FAR_LEAD_S).coerceIn(FAR_MIN_M, FAR_MAX_M)
        val marker = ANNOUNCE_MARKERS_M.firstOrNull { it <= horizon && meters - lead <= it }
        if (marker != null && secondsAway > FAR_MIN_GAP_S) {
            announcedFar = true
            speaker.speak(GuidanceText.spokenFar(target, marker))
        }
    }

    /** Thin the route down to a corridor of sample points and remember its bounding box. */
    private fun buildCorridor(route: Route?) {
        val points = route?.points.orEmpty()
        if (points.size < 2) {
            corridor = emptyList()
            return
        }
        val kept = ArrayList<GeoPoint>(points.size / 8 + 2)
        kept.add(points.first())
        var since = 0.0
        for (i in 1 until points.size) {
            since += Geo.haversine(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
            if (since >= CORRIDOR_STEP_M) {
                kept.add(points[i])
                since = 0.0
            }
        }
        kept.add(points.last())
        corridor = kept
        val padLat = NAV_ALERT_RADIUS_M / 111_000.0
        val midLat = kept[kept.size / 2].lat
        val padLon = padLat / cos(Math.toRadians(midLat)).coerceAtLeast(0.1)
        corridorMinLat = kept.minOf { it.lat } - padLat
        corridorMaxLat = kept.maxOf { it.lat } + padLat
        corridorMinLon = kept.minOf { it.lon } - padLon
        corridorMaxLon = kept.maxOf { it.lon } + padLon
    }

    /**
     * Is the report on the road being driven? Only knowable while navigating, where
     * the route says how far off it sits; free driving assumes it is (better a spare
     * alert than a missed one).
     */
    private fun onSameRoad(report: UserReport): Boolean {
        val rp = path ?: return true
        val match = rp.match(report.lat, report.lon) ?: return true
        return match.offRouteMeters <= SAME_ROAD_M
    }

    /** Is this alert worth showing during the trip: near the route, or near the driver? */
    private fun onTripRoute(lat: Double, lon: Double, here: LocationSample): Boolean {
        if (Geo.haversine(here.latitude, here.longitude, lat, lon) <= NAV_ALERT_RADIUS_M) return true
        val points = corridor
        if (points.isEmpty()) return false
        if (lat < corridorMinLat || lat > corridorMaxLat || lon < corridorMinLon || lon > corridorMaxLon) return false
        return points.any { Geo.haversine(lat, lon, it.lat, it.lon) <= NAV_ALERT_RADIUS_M }
    }

    /** Distance along the route of every maneuver — monotonic, so progress can't jump back. */
    private fun buildStepAlong(rp: RoutePath?, route: Route?): DoubleArray {
        val steps = route?.steps ?: return DoubleArray(0)
        if (rp == null || steps.isEmpty()) return DoubleArray(0)
        var last = 0.0
        return DoubleArray(steps.size) { i ->
            val m = rp.match(steps[i].location.lat, steps[i].location.lon)
            val along = (m?.alongMeters ?: last).coerceAtLeast(last)
            last = along
            along
        }
    }

    override fun onCleared() {
        speaker.shutdown()
        sounds.release()
        super.onCleared()
    }

    /** A new destination starts a trip, or redirects the one running (a new estimate follows). */
    private fun startTrip(destination: Place) {
        val current = trip
        if (current == null) trip = TripRecorder(destination.name) else current.retarget(destination.name)
    }

    /** Save the finished trip if it is worth keeping, locally and on the server. */
    private fun finalizeTrip() {
        val finished = trip ?: return
        trip = null
        val record = finished.record(UUID.randomUUID().toString()) ?: return
        tripHistory.add(record)
        // Statistics live on the server for everyone: survive a reinstall.
        viewModelScope.launch { AccountRepository.postTrip(record) }
    }

    /**
     * The signs of [route] (already filtered by the backend for the way it runs, limit changes
     * included), and those limit changes placed along it for [routeLimits].
     */
    private suspend fun loadRouteSigns(route: Route?) {
        val points = route?.points?.takeIf { it.size >= 2 }
        val rp = points?.let { RoutePath(it) }
        val list = if (points == null) {
            emptyList()
        } else {
            // Without an answer the signs already shown stay, and the route asks again, less and
            // less often, until it gets them or is replaced.
            var answer = signApi.route(points)
            var wait = SIGNS_RETRY_MS
            while (answer == null) {
                delay(wait)
                if (ActiveTripRepository.route.value !== route) return
                wait = (wait * 2).coerceAtMost(SIGNS_RETRY_MAX_MS)
                answer = signApi.route(points)
            }
            answer
        }
        signs.value = list
        routeLimits = if (rp == null) {
            emptyList()
        } else {
            list.mapNotNull { sign ->
                val kmh = sign.speed?.takeIf { sign.type == SignType.SpeedLimit } ?: return@mapNotNull null
                rp.match(sign.lat, sign.lon)?.let { it.alongMeters to kmh }
            }.sortedBy { it.first }
        }
        routeLimitPath = rp
        if (rp == null) limitFromRoute = false
    }

    /** Reports are few enough to hold the whole country at once. */
    private fun radiusM(): Int = FULL_LOAD_M

    /** Route constraints the driver asked for, as the backend expects them. */
    private fun avoidOptions(): List<String> {
        val s = AppPreferences.settings.value
        return buildList {
            if (s.avoidTolls) add("tolls")
            if (s.avoidHighways) add("highways")
            if (s.avoidTraffic) add("traffic")
        }
    }

    /**
     * Keep [radars] to what matters, the way the road signs work: the radars along the
     * whole route while navigating (one request per route), a [RADAR_RING_M] ring around
     * the driver otherwise, reloaded every [RADAR_RING_REFRESH_M]. A route the backend
     * cannot answer for falls back to the ring; a failed request keeps the current list.
     */
    private suspend fun refreshRadars(route: Route?, fix: LocationSample?) {
        val trip = route?.takeIf { it.points.size >= 2 }
        if (trip == null) {
            // Trip over: the ring around the driver comes back right away.
            if (radarsRoute != null) ringLat = Double.NaN
            radarsRoute = null
            radarsOnRoute = false
        } else if (trip !== radarsRoute) {
            radarsRoute = trip
            val onRoute = radarRepository.route(trip.points)
            radarsOnRoute = onRoute != null
            if (onRoute != null) radars.value = onRoute else ringLat = Double.NaN
        }
        if (radarsOnRoute) return

        val here = fix ?: return
        val now = System.currentTimeMillis()
        if (now < ringRetryAt) return
        val moved = ringLat.isNaN() ||
            Geo.haversine(ringLat, ringLon, here.latitude, here.longitude) >= RADAR_RING_REFRESH_M
        if (!moved) return
        val ring = radarRepository.near(here.latitude, here.longitude, RADAR_RING_M)
        if (ring != null) {
            radars.value = ring
            ringLat = here.latitude
            ringLon = here.longitude
        } else {
            ringRetryAt = now + RADAR_RETRY_MS
        }
    }

    private suspend fun refreshReports(lat: Double, lon: Double) {
        // A failed reload keeps the reports already shown: a dropped connection is not an empty road.
        val near = reportsRepository.near(lat, lon, radiusM()) ?: return
        reports.value = near.reports.filterNot { it.id in deniedReports }
        zones.value = near.zones
    }

    /**
     * Post a crowdsourced report at the current position (optimistically shown).
     * Extra fields ([plate] for a radar car, [street]/[side] for a camera) are
     * validated server-side against the account's role.
     */
    /**
     * Post a report. A camera carries a geocoded address, so it is filed there;
     * everything else is filed where the driver is.
     */
    fun report(draft: ReportDraft) {
        val fix = LocationRepository.location.value ?: return
        val lat = fix.latitude
        val lon = fix.longitude
        viewModelScope.launch {
            val created = try {
                reportsRepository.create(
                    NewReport(
                        draft.type,
                        lat,
                        lon,
                        plate = draft.plate,
                        direction = draft.direction,
                        bearingDeg = fix.bearingDeg?.toDouble(),
                    ),
                    AccountRepository.token,
                    AccountRepository.deviceId,
                )
            } catch (e: AccessDeniedException) {
                // Trial over, or today's reports used: the offers show instead.
                OffersPrompt.show(PaywallReason.of(e.denial))
                AccountRepository.reload()
                return@launch
            }
            // Radar cars appear as zones, not points → refetch to get the new zone. A report the
            // backend merged into one already there comes back as that one: replaced, not doubled.
            if (created != null && draft.type != ReportType.VoitureRadar) {
                reports.value = reports.value.filterNot { it.id == created.id } + created
            }
            // A guest's count of the day moved on.
            if (created != null && AccountRepository.account.value?.limits != null) AccountRepository.reload()
            refreshReports(lat, lon)
        }
    }

    /** Admin moderation: delete a report, then refetch. */
    fun deleteReport(reportId: String) {
        viewModelScope.launch {
            reportsRepository.delete(reportId, AccountRepository.token)
            reports.value = reports.value.filterNot { it.id == reportId }
            LocationRepository.location.value?.let { fix -> refreshReports(fix.latitude, fix.longitude) }
        }
    }

    private val _votedReports = MutableStateFlow<Set<String>>(emptySet())

    /** Reports the driver voted on in this session: their "toujours là / plus là" go away. */
    val votedReports: StateFlow<Set<String>> get() = _votedReports

    /** Reports the driver said are gone: never shown again to them, whatever the crowd says. */
    private val deniedReports = HashSet<String>()

    /** Community vote on a report ("toujours là" / "plus là"), one voice per person. */
    fun vote(reportId: String, confirm: Boolean) {
        _votedReports.value = _votedReports.value + reportId
        if (!confirm) {
            deniedReports.add(reportId)
            reports.value = reports.value.filterNot { it.id == reportId }
        }
        viewModelScope.launch {
            reportsRepository.vote(reportId, confirm, AccountRepository.token, AccountRepository.deviceId)
            LocationRepository.location.value?.let { fix -> refreshReports(fix.latitude, fix.longitude) }
        }
    }

    /**
     * Propose [newKmh] as the limit where the driver is: sign maintenance, not a road event.
     * The HUD keeps its limit until the backend validates the change; when this proposal is
     * the one that tips it, the new limit shows at once, on the route's signs too.
     */
    fun reportSpeedLimit(newKmh: Int) {
        val fix = LocationRepository.location.value ?: return
        val shown = driveState.value
        viewModelScope.launch {
            val change = speedLimitRepository.report(
                NewSpeedLimitReport(
                    lat = fix.latitude,
                    lon = fix.longitude,
                    bearingDeg = fix.bearingDeg?.toDouble(),
                    displayedKmh = shown.speedLimitKmh,
                    displayedSource = shown.speedLimitSource,
                    newKmh = newKmh,
                ),
                AccountRepository.token,
                AccountRepository.deviceId,
            ) ?: return@launch
            if (change.status == SpeedLimitChange.Status.Validated && change.newKmh != null) {
                osmLimit.value = change.newKmh
                ActiveTripRepository.route.value?.let { route -> loadRouteSigns(route) }
            }
        }
    }

    private val _dismissedAlerts = MutableStateFlow<Set<String>>(emptySet())
    private val dismissJobs = HashMap<String, kotlinx.coroutines.Job>()

    /** Keys of the alerts swiped off the HUD. Each one comes back after [ALERT_DISMISS_MS]. */
    val dismissedAlerts: StateFlow<Set<String>> get() = _dismissedAlerts

    /**
     * Hide one alert from the HUD for [ALERT_DISMISS_MS] — a driver waiting still would
     * otherwise keep it on screen. Display only: the alert stays live for the voice and the
     * trip's count, and shows again afterwards if it is still ahead.
     */
    fun dismissAlert(key: String) {
        _dismissedAlerts.value = _dismissedAlerts.value + key
        dismissJobs.remove(key)?.cancel()
        dismissJobs[key] = viewModelScope.launch {
            delay(ALERT_DISMISS_MS)
            _dismissedAlerts.value = _dismissedAlerts.value - key
            dismissJobs.remove(key)
        }
    }

    /** The HUD's music button, the banner's controls and its empty states. */
    fun onMusic(action: MusicAction) {
        when (action) {
            MusicAction.ToggleBanner -> musicOpen.value = !musicOpen.value
            MusicAction.PlayPause -> MediaRepository.playPause()
            MusicAction.Next -> MediaRepository.next()
            MusicAction.Previous -> MediaRepository.previous()
            MusicAction.OpenAccessSettings -> MediaRepository.openAccessSettings()
        }
    }

    /** The driving screen is in front again: notification access may have changed meanwhile. */
    fun onHudStarted() = MediaRepository.refresh()

    private val announcedAlerts = HashSet<String>()
    private var lastOverspeedAt = 0L

    /** Speak an approaching radar/report at ~500 m then ~200 m, once each. */
    private fun announceAlert(alert: RoadAlert?) {
        val id = alert?.id ?: return
        val d = alert.distanceMeters
        if (announcedAlerts.size > 300) announcedAlerts.clear()
        fun say(step: Int, text: String) {
            if (announcedAlerts.add("$id@$step")) speaker.speak(text)
        }
        when {
            d in (VOICE_NEAR_M + 1)..VOICE_FAR_M -> {
                val vma = alert.speedLimitKmh
                val dist = GuidanceText.spokenDistance(d)
                say(VOICE_FAR_M, if (vma != null) "${alert.title} dans $dist, vitesse $vma." else "${alert.title} dans $dist.")
            }
            d in 0..VOICE_NEAR_M -> say(VOICE_NEAR_M, "${alert.title}${alert.roadLabel?.let { ", $it" } ?: ""}.")
        }
    }

    /**
     * Once when clearly over the limit, then an occasional reminder while it lasts: spoken or
     * beeped, as "Dépassement limitation" says, and only while the voice or the sound is on.
     */
    private fun warnOverspeed(speedKmh: Int, limitKmh: Int?, prefs: AlertPreferences) {
        if (limitKmh == null || limitKmh <= 0) return
        if (speedKmh <= limitKmh + OVERSPEED_MARGIN) {
            overspeeding = false
            return
        }
        // Now that a real road limit is known everywhere, speak once when the driver
        // goes over and only remind them occasionally while they stay there.
        val now = System.currentTimeMillis()
        if (overspeeding && now - lastOverspeedAt < OVERSPEED_COOLDOWN_MS) return
        overspeeding = true
        lastOverspeedAt = now
        when (prefs.overspeed) {
            OverspeedWarning.Voice -> if (prefs.voice) speaker.speak("Vous dépassez la limite de $limitKmh.")
            OverspeedWarning.Beep -> if (prefs.sound) sounds.play(AlertSound.Overspeed, prefs.vibration)
            OverspeedWarning.Off -> Unit
        }
    }

    /**
     * A sound as each alert shows up: the detector's chirps for speed enforcement, a chime for a
     * road hazard. One sound for several alerts appearing together.
     */
    private fun soundNewAlerts(alerts: List<RoadAlert>, vibrate: Boolean) {
        if (soundedAlerts.size > 300) {
            soundedAlerts.clear()
            burstAlerts.clear()
        }
        val fresh = alerts.filter { soundedAlerts.add(it.key) }
        if (fresh.isEmpty()) return
        sounds.play(if (fresh.any { it.type.isEnforcement }) AlertSound.Detector else AlertSound.Hazard, vibrate)
    }

    /** Every report ahead still worth an alert for this driver → [RoadAlert]s, nearest first. */
    private fun reportsAhead(
        reportList: List<UserReport>,
        sample: LocationSample?,
        speedKmh: Int,
    ): List<RoadAlert> {
        if (sample == null || reportList.isEmpty()) return emptyList()
        val heading = sample.bearingDeg?.toDouble()
        val metersPerSecond = (speedKmh * 1000f / 3600f).coerceAtLeast(1f)
        return reportList
            .map { report -> report to Geo.haversine(sample.latitude, sample.longitude, report.lat, report.lon) }
            .filter { (report, _) ->
                heading == null || Geo.angularDiff(
                    heading,
                    Geo.bearing(sample.latitude, sample.longitude, report.lat, report.lon),
                ) <= AHEAD_CONE_DEG
            }
            .sortedBy { it.second }
            // Never shown before the alert distance, whatever the category's impact zone.
            .filter { (_, distance) -> distance <= ALERT_DISTANCE_M }
            // Each category has its own impact zone: an accident is worth knowing 2 km
            // ahead, a camera only at 300 m.
            // Worth an alert when the full score — time, crowd, road, direction and
            // distance — is still above the minimum for this particular driver.
            .filter { (report, distance) ->
                ReportRelevance.score(report, distance, heading, onSameRoad(report)) >= ReportRelevance.MINIMUM
            }
            .map { (report, distance) ->
                RoadAlert(
                    type = report.type.alertType,
                    title = report.type.label,
                    // Say when it is on the other carriageway: it changes what the driver does.
                    roadLabel = if (report.direction == "opposite") report.directionLabel else report.sideLabel?.let { "côté $it" },
                    speedLimitKmh = null,
                    distanceMeters = distance.roundToInt(),
                    etaSeconds = (distance / metersPerSecond).roundToInt(),
                    confidence = (
                        ReportRelevance.score(report, distance, heading, onSameRoad(report)) / 100.0
                    ).toFloat().coerceIn(0f, 1f),
                    // Who saw it and when: the two things that tell a driver if it is still there.
                    lastReportedLabel = report.crowdLabel,
                    id = report.id,
                )
            }
    }

    private fun shouldFetch(sample: LocationSample): Boolean {
        if (lastFetchLat.isNaN()) return true
        return Geo.haversine(lastFetchLat, lastFetchLon, sample.latitude, sample.longitude) > FETCH_MOVE_M
    }

    private fun tripFrom(route: Route?): TripInfo? {
        if (route == null) return null
        val minutes = (route.durationSeconds / 60.0).roundToInt()
        val remaining = if (minutes >= 60) "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}" else "$minutes min"
        val km = route.distanceMeters / 1000.0
        val distance = if (km >= 10) "${km.roundToInt()} km" else "%.1f km".format(km).replace('.', ',')
        val arrival = LocalTime.now().plusSeconds(route.durationSeconds.toLong()).format(HHMM)
        return TripInfo(remainingLabel = remaining, distanceLabel = distance, arrivalLabel = arrival)
    }

    /** Every radar ahead within alert range → alerts, nearest first; nearest speed radar ahead → the active limit. */
    private fun relevantAheadOf(
        radarList: List<Radar>,
        sample: LocationSample?,
        speedKmh: Int,
    ): Pair<List<RoadAlert>, Int?> {
        if (sample == null || radarList.isEmpty()) return emptyList<RoadAlert>() to null
        val heading = sample.bearingDeg?.toDouble()

        val ahead = radarList
            .map { radar -> radar to Geo.haversine(sample.latitude, sample.longitude, radar.lat, radar.lon) }
            .filter { (radar, _) ->
                heading == null || Geo.angularDiff(
                    heading,
                    Geo.bearing(sample.latitude, sample.longitude, radar.lat, radar.lon),
                ) <= AHEAD_CONE_DEG
            }
            .sortedBy { it.second }

        val limit = ahead.firstOrNull { it.first.isSpeedRadar && it.second <= LIMIT_DISTANCE_M }?.first?.vma

        // Every radar within alert range ahead, not only the first one.
        val metersPerSecond = (speedKmh * 1000f / 3600f).coerceAtLeast(1f)
        val alerts = ahead
            .takeWhile { it.second <= ALERT_DISTANCE_M }
            .map { (radar, distance) ->
                RoadAlert(
                    type = radar.alertType,
                    title = radar.displayTitle,
                    roadLabel = null,
                    speedLimitKmh = radar.vma,
                    distanceMeters = distance.roundToInt(),
                    etaSeconds = (distance / metersPerSecond).roundToInt(),
                    confidence = 1f,
                    lastReportedLabel = null,
                    id = radar.id,
                )
            }

        return alerts to limit
    }

    private companion object {
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        const val STOP_TIMEOUT_MS = 5_000L
        // Reports: everything in France comes in one go, so the move-based refetch only
        // catches changes after a long drive (they also refresh every REPORT_REFRESH_MS).
        const val FULL_LOAD_M = 1_000_000
        const val FETCH_MOVE_M = 50_000.0
        // Radars outside a trip: a 22 km ring, reloaded every 5 km so at least 17 km ahead
        // is always covered; a failed request is retried after 20 s.
        const val RADAR_RING_M = 22_000
        const val RADAR_RING_REFRESH_M = 5_000.0
        const val RADAR_RETRY_MS = 20_000L
        const val REPORT_REFRESH_MS = 25_000L
        /** The app tells the backend it is open this often (the backend forgets it after 90 s). */
        const val PRESENCE_MS = 30_000L
        const val NAV_ALERT_RADIUS_M = 15000.0
        /** Spacing of the route corridor samples — well under the radius above. */
        const val CORRIDOR_STEP_M = 2_000.0
        // Trip recording.
        const val ARRIVE_M = 45.0
        const val TRIP_MIN_STEP_M = 1.0
        const val TRIP_MAX_STEP_M = 250.0
        // Drive-time accounting: moving above ~5 km/h, gaps over 10 s ignored, synced per minute.
        const val DRIVE_MIN_SPEED_MS = 1.5f
        const val DRIVE_MAX_GAP_S = 10.0
        const val DRIVE_FLUSH_MS = 60_000L
        /** How far ahead a radar or a report shows as an alert. */
        const val ALERT_DISTANCE_M = 700.0
        // The VMA sign shows while a speed radar is the active alert ahead. (Road-wide
        // limits everywhere need an OSM maxspeed source — planned separately.)
        const val LIMIT_DISTANCE_M = 1000.0
        const val AHEAD_CONE_DEG = 75.0
        const val OFF_ROUTE_M = 45.0
        const val RECALC_COOLDOWN_MS = 2_500L
        /** A trip's first route: asked again after these pauses before giving up. */
        val ROUTE_RETRY_MS = longArrayOf(1_200L, 3_000L, 6_000L)
        /** Route signs not loaded: asked again after 3 s, then less often, up to every 30 s. */
        const val SIGNS_RETRY_MS = 3_000L
        const val SIGNS_RETRY_MAX_MS = 30_000L
        // Turn-by-turn thresholds.
        const val STEP_PASSED_M = 12.0
        const val NEAR_ANNOUNCE_M = 45
        const val NEAR_ANNOUNCE_S = 4.0
        const val FAR_MIN_M = 150.0
        const val FAR_MAX_M = 1000.0
        // Voice calibration: how long the phrase takes to reach its distance word, how
        // far ahead the heads-up looks, and the minimum gap before the final cue.
        const val SPEECH_LEAD_S = 2.0
        const val FAR_LEAD_S = 14.0
        const val FAR_MIN_GAP_S = 7.0
        const val ON_ROUTE_M = 45.0
        /** Off the route by more than this and the report is on another road. */
        const val SAME_ROAD_M = 60.0
        /** Round distances the voice is allowed to announce (descending). */
        val ANNOUNCE_MARKERS_M = intArrayOf(1000, 700, 500, 300, 200, 150, 100)
        // Live speed limit polling.
        const val LIMIT_MOVE_M = 40.0
        /** Farther than this from the route, its limits are not the driver's. */
        const val ROUTE_LIMIT_MAX_OFF_M = 30.0
        const val LIMIT_POLL_MS = 2_500L
        const val LIMIT_STALE_MS = 25_000L
        // Alert voice.
        const val VOICE_FAR_M = 500
        const val VOICE_NEAR_M = 200
        const val OVERSPEED_MARGIN = 5
        const val OVERSPEED_COOLDOWN_MS = 60_000L
        /** How often the proximity beeps check the nearest alert. */
        const val BEEP_TICK_MS = 100L
        // An alert swiped off the HUD stays hidden this long, then shows again if still live.
        const val ALERT_DISMISS_MS = 2 * 60_000L
    }
}
