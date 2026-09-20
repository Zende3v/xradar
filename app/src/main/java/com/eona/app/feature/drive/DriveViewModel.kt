package com.eona.app.feature.drive

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eona.app.core.drive.AlertBeeps
import com.eona.app.core.drive.TripRecorder
import com.eona.app.core.geo.Geo
import com.eona.app.core.geo.GuidanceSides
import com.eona.app.core.geo.GuidanceText
import com.eona.app.core.geo.RoutePath
import com.eona.app.core.model.GeoPoint
import com.eona.app.core.model.GpsSignal
import com.eona.app.core.model.GuidanceInstruction
import com.eona.app.core.model.RouteTraffic
import com.eona.app.core.model.LocationSample
import com.eona.app.core.model.Place
import com.eona.app.core.model.Radar
import com.eona.app.core.model.ReportRelevance
import com.eona.app.core.model.RadarZone
import com.eona.app.core.model.ReportType
import com.eona.app.core.model.RoadAlert
import com.eona.app.core.model.Route
import com.eona.app.core.model.RouteStep
import com.eona.app.core.model.SignType
import com.eona.app.core.model.SpeedLimitChange
import com.eona.app.core.model.SpeedLimitSource
import com.eona.app.core.model.TripInfo
import com.eona.app.core.model.TripRecord
import com.eona.app.core.model.UserReport
import com.eona.app.core.model.isEnforcement
import com.eona.app.data.account.AccountRepository
import com.eona.app.data.preferences.AlertPreferences
import com.eona.app.data.preferences.AppPreferences
import com.eona.app.data.preferences.OverspeedWarning
import com.eona.app.data.radar.RadarRepository
import com.eona.app.data.reports.NewReport
import com.eona.app.data.reports.ReportsRepository
import com.eona.app.data.routing.ActiveTripRepository
import com.eona.app.data.routing.RouteAnswer
import com.eona.app.data.routing.RoutingRepository
import com.eona.app.data.account.AccessDeniedException
import com.eona.app.feature.subscription.OffersPrompt
import com.eona.app.feature.subscription.PaywallReason
import com.eona.app.data.speedlimits.NewSpeedLimitReport
import com.eona.app.data.speedlimits.SpeedLimitRepository
import com.eona.app.data.stats.TripHistoryRepository
import com.eona.app.feature.drive.component.key
import com.eona.app.location.LocationRepository
import com.eona.app.media.MediaRepository
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
    private val liveApi = com.eona.app.data.live.LiveApi()
    private val tripHistory = TripHistoryRepository(application)
    private val radars = MutableStateFlow<List<Radar>>(emptyList())
    private val reports = MutableStateFlow<List<UserReport>>(emptyList())
    private val zones = MutableStateFlow<List<RadarZone>>(emptyList())
    private val signApi = com.eona.app.data.signs.SignApi()
    private val signs = MutableStateFlow<List<com.eona.app.core.model.RoadSign>>(emptyList())
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
    /** Traffic on the route being followed, measured along it; null until known. */
    private val traffic = MutableStateFlow<com.eona.app.core.model.RouteTraffic?>(null)
    private val trafficApi = com.eona.app.data.traffic.TrafficApi()
    /** Bumped at each new route: an answer about a route since replaced is dropped. */
    private var routeVersion = 0
    /** Shown a few seconds after a switch to a faster route. */
    private val fasterNotice = MutableStateFlow<FasterRouteNotice?>(null)
    private val routingApi = com.eona.app.data.routing.RoutingApi()
    // "Éviter les bouchons": a check running, the last one asked, and the trip's last switch for
    // traffic, for the destination they were about (the same place chosen again keeps them).
    private var checkingFaster = false
    private var lastFasterCheckAt = 0L
    private var lastTrafficRerouteAt: Long? = null
    private var fasterDestinationId: String? = null
    // "Partager les ralentissements": the detector (fed once per fix), the question asked, and
    // where the driver said "Non" lately.
    private val slowdownDetector = com.eona.app.core.drive.SlowdownDetector()
    private var lastDetectedFixMs = Long.MIN_VALUE
    private val slowdownPrompt = MutableStateFlow<SlowdownPrompt?>(null)
    /** The arrival card, once the destination is reached. */
    private val arrival = MutableStateFlow<TripArrival?>(null)
    /** True when the trip ended at its destination, as opposed to being stopped on the way. */
    private var arrived = false
    private val declinedSlowdowns = ArrayList<Triple<Double, Double, Long>>()
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
    /** Where the last recalculation was asked from: the next one waits for real driving. */
    private var recalcLat = Double.NaN
    private var recalcLon = Double.NaN
    private var recalcWaitMs = RECALC_COOLDOWN_MS
    /** True once the driver has actually been on the route: before that the trip has not started. */
    private var joinedRoute = false
    /** When the current route started waiting to be joined (0: none waiting). */
    private var waitingSince = 0L
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
        // Off a trip, the reports within the radars' ring around the driver only: the whole of
        // France on the map is no help, whoever reported (admins included).
        val shownReports = when {
            onTrip -> enabledReports.filter { onTripRoute(it.lat, it.lon, here!!) }
            here != null -> enabledReports.filter { Geo.haversine(here.latitude, here.longitude, it.lat, it.lon) <= RADAR_RING_M }
            else -> enabledReports
        }
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
    }.combine(traffic) { state, t ->
        state.copy(traffic = if (state.routePoints.isEmpty()) null else t)
    }.combine(fasterNotice) { state, notice ->
        state.copy(fasterNotice = notice)
    }.combine(slowdownPrompt) { state, prompt ->
        state.copy(slowdownPrompt = prompt)
    }.combine(arrival) { state, reached ->
        state.copy(arrival = reached)
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
                // "Statistiques de conduite" off: nothing counted, nothing sent.
                if (!AppPreferences.settings.value.drivingStats) {
                    lastLat = Double.NaN
                    pendingS = 0.0
                    pendingM = 0.0
                    return@collect
                }
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
        // "Éviter les bouchons" turned on during a trip: the traffic already known is looked at now.
        viewModelScope.launch {
            AppPreferences.settings.map { it.avoidTraffic }.distinctUntilChanged().collect { on ->
                val known = traffic.value
                if (on && known != null) considerFasterRoute(known, routeVersion)
            }
        }
        // The traffic on the route being followed, every two minutes while a trip runs (a new
        // route asks at once). Nothing is fetched without a trip.
        viewModelScope.launch {
            while (true) {
                delay(TRAFFIC_REFRESH_MS)
                refreshTraffic(routeVersion)
            }
        }
        // Presence: the app says it is open, and whether a trip runs. The position goes with it
        // only with "Présence et position", the time spent only with "Temps d'utilisation":
        // both switches off, nothing is sent at all.
        viewModelScope.launch {
            while (true) {
                val privacy = AppPreferences.settings.value
                AccountRepository.token?.takeIf { privacy.presence || privacy.usageTime }?.let { token ->
                    val fix = LocationRepository.location.value.takeIf { privacy.presence }
                    liveApi.presence(
                        token,
                        inTrip = ActiveTripRepository.destination.value != null,
                        position = fix?.let { GeoPoint(it.latitude, it.longitude) },
                        speedKmh = fix?.speedKmh?.roundToInt()?.coerceAtLeast(0),
                        countTime = privacy.usageTime,
                    )
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
                .map { it.avoidTolls to it.avoidHighways }
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
                if (destination.id != fasterDestinationId) {
                    fasterDestinationId = destination.id
                    lastTrafficRerouteAt = null
                    lastFasterCheckAt = 0L
                }
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
                    if (toDest < ARRIVE_M && current.distanceMeters >= TripRecorder.MIN_METERS) {
                        arrived = true
                        ActiveTripRepository.clear()
                    }
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
                detectSlowdown(s)
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
                    if (burstAlerts.add(nearest.key)) sounds.play(AlertSound.Laser, prefs.vibration, prefs.alertVolume)
                    continue
                }
                val interval = AlertBeeps.intervalMs(nearest.distanceMeters) ?: continue
                val now = SystemClock.elapsedRealtime()
                if (now - lastBeepAt < interval) continue
                lastBeepAt = now
                sounds.play(AlertSound.Beep, prefs.vibration, prefs.alertVolume)
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
                // On the route: the trip has really started, and a detour may be corrected later.
                if (offBy <= OFF_ROUTE_M) {
                    joinedRoute = true
                    recalcWaitMs = RECALC_COOLDOWN_MS
                    return@collect
                }
                val speed = sample.speedMps ?: 0f
                // Not joined yet: the driver is simply not there — in a building, a car park, a
                // lane the routing does not know. Nothing to correct until they really drive, and
                // a route nobody ever joins is dropped instead of waiting forever.
                if (!joinedRoute) {
                    if (waitingSince == 0L) waitingSince = now
                    if (speed < DRIVE_MIN_SPEED_MS && now - waitingSince > TRIP_ABANDON_MS) {
                        ActiveTripRepository.clear()
                        return@collect
                    }
                }
                if (speed < if (joinedRoute) DRIVE_MIN_SPEED_MS else RECALC_START_SPEED_MS) return@collect
                // Standing still, or barely moved since the last one: asking again would give the
                // same answer. Only real driving earns a new route.
                val moved = if (recalcLat.isNaN()) {
                    Double.MAX_VALUE
                } else {
                    Geo.haversine(recalcLat, recalcLon, sample.latitude, sample.longitude)
                }
                if (moved < if (joinedRoute) RECALC_MIN_MOVE_M else RECALC_START_MOVE_M) return@collect
                if (now - lastRecalcAt < recalcWaitMs) return@collect
                lastRecalcAt = now
                recalcLat = sample.latitude
                recalcLon = sample.longitude
                val fresh = routingRepository.route(
                    GeoPoint(sample.latitude, sample.longitude),
                    GeoPoint(destination.lat, destination.lon),
                    avoidOptions(),
                ).routeOrNull
                if (fresh != null) {
                    recalcWaitMs = RECALC_COOLDOWN_MS
                    ActiveTripRepository.setRoute(fresh)
                } else {
                    // No answer: wait longer each time instead of asking again straight away.
                    recalcWaitMs = (recalcWaitMs * 2).coerceAtMost(RECALC_WAIT_MAX_MS)
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
                // Another route: it has to be joined in its turn (a recalculation can start on a
                // road the driver is not on yet).
                joinedRoute = false
                waitingSince = 0L
                stepIndex = 1
                announcedFar = false
                announcedNear = false
                path = route?.points?.takeIf { it.size >= 2 }?.let { RoutePath(it) }
                stepAlong = buildStepAlong(path, route)
                guidanceSteps = GuidanceSides.checked(route?.steps.orEmpty(), stepAlong, path)
                buildCorridor(route)
                // Another route, another geometry: its traffic is asked for at once.
                routeVersion += 1
                traffic.value = null
                val version = routeVersion
                viewModelScope.launch { refreshTraffic(version) }
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
            speaker.speak(GuidanceText.spokenNear(target), AppPreferences.alerts.value.guidanceVolume)
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
            speaker.speak(GuidanceText.spokenFar(target, marker), AppPreferences.alerts.value.guidanceVolume)
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
        // Arrived, not stopped on the way: the HUD says so before going back to simply driving.
        if (arrived) {
            arrived = false
            showArrival(finished)
        }
        // "Statistiques de conduite" off: the trip only served the guidance (its arrival).
        if (!AppPreferences.settings.value.drivingStats) return
        val record = finished.record(UUID.randomUUID().toString()) ?: return
        tripHistory.add(record)
        // Statistics live on the server for everyone: survive a reinstall.
        viewModelScope.launch { AccountRepository.postTrip(record) }
    }

    /** The destination is reached: the card, with the trip's figures, for a few seconds. */
    private fun showArrival(finished: TripRecorder) {
        val reached = TripArrival(
            toLabel = finished.toLabel,
            distanceMeters = finished.distanceMeters.roundToInt(),
            durationSeconds = ((System.currentTimeMillis() - finished.startedAt) / 1000).toInt(),
            alertsCount = finished.alertsMet,
        )
        arrival.value = reached
        viewModelScope.launch {
            delay(ARRIVAL_MS)
            if (arrival.value == reached) arrival.value = null
        }
    }

    /** The driver closed the arrival card. */
    fun dismissArrival() {
        arrival.value = null
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

    /**
     * A failed request keeps the colours shown; an answer for a route since replaced is dropped.
     * The driver's progress goes along: the backend says whether a faster route may exist ahead.
     */
    private suspend fun refreshTraffic(version: Int) {
        val route = ActiveTripRepository.route.value?.takeIf { it.points.size >= 2 } ?: return
        val fresh = trafficApi.route(route.points, progress()?.alongMeters, AccountRepository.token) ?: return
        if (version != routeVersion) return
        traffic.value = fresh
        considerFasterRoute(fresh, version)
    }

    /**
     * "Éviter les bouchons": when the backend says the traffic ahead (TomTom's and the drivers'
     * jams) may be worth going around, it looks for a faster way, and the trip takes it when it
     * saves enough time (the backend's thresholds, stricter a while after a switch) or goes
     * around a closed road. Never a detour for a jam alone, never within a few minutes of the
     * last switch, never for a simulated trip; asked again every few minutes at most (each
     * check costs several TomTom and ORS requests).
     */
    private suspend fun considerFasterRoute(known: RouteTraffic, version: Int) {
        if (!known.worthChecking || !AppPreferences.settings.value.avoidTraffic || checkingFaster) return
        val destination = ActiveTripRepository.destination.value ?: return
        val rp = path ?: return
        val now = System.currentTimeMillis()
        lastTrafficRerouteAt?.let { if (now - it < FASTER_COOLDOWN_MS) return }
        if (now - lastFasterCheckAt < FASTER_RECHECK_MS) return
        val match = progress() ?: return
        checkingFaster = true
        lastFasterCheckAt = now
        try {
            val since = lastTrafficRerouteAt?.let { ((now - it) / 1000).toInt() }
            val faster = routingApi.faster(rp.trimFrom(match.alongMeters), avoidOptions(), since) ?: return
            if (version != routeVersion || ActiveTripRepository.destination.value != destination) return
            lastTrafficRerouteAt = System.currentTimeMillis()
            ActiveTripRepository.setRoute(faster.route)
            announceFaster(FasterRouteNotice(maxOf(1, (faster.gainSeconds / 60.0).roundToInt()), faster.closed))
        } finally {
            checkingFaster = false
        }
    }

    /**
     * "Partager les ralentissements": a crawl on a fast road ([SlowdownDetector], fed once per
     * fix) goes to the backend, anonymously; when no jam is known there yet, the driver is asked
     * "Ralentissement du trafic ?" for a few seconds. Nothing while the trip starts or ends.
     */
    private fun detectSlowdown(state: DriveUiState) {
        val fix = state.location ?: return
        if (fix.timeMs == lastDetectedFixMs) return
        lastDetectedFixMs = fix.timeMs
        if (!AppPreferences.settings.value.sharedTraffic || AccountRepository.token == null) return
        val destination = ActiveTripRepository.destination.value
        val paused = destination != null && (
            (trip?.distanceMeters ?: 0.0) < SLOWDOWN_TRIP_START_M ||
                Geo.haversine(fix.latitude, fix.longitude, destination.lat, destination.lon) < SLOWDOWN_TRIP_END_M
            )
        val slowdown = slowdownDetector.update(
            sample = fix,
            speedKmh = state.speedKmh,
            limitKmh = state.speedLimitKmh,
            limitFromRoad = state.speedLimitSource == SpeedLimitSource.Road,
            paused = paused,
        ) ?: return
        viewModelScope.launch { shareSlowdown(slowdown) }
    }

    private suspend fun shareSlowdown(slowdown: com.eona.app.core.drive.Slowdown) {
        val known = trafficApi.probe(slowdown, AccountRepository.token)
        val now = System.currentTimeMillis()
        declinedSlowdowns.removeAll { it.third <= now }
        // Known to the backend, to the trip's traffic here, or a "Bouchon" close by: nothing to
        // ask. A blocked account is not asked either (it could not report).
        if (known != false || slowdownPrompt.value != null || AccountRepository.account.value?.isRestricted == true) return
        if (trafficKnownHere(slowdown)) return
        if (declinedSlowdowns.any { Geo.haversine(it.first, it.second, slowdown.lat, slowdown.lon) < SLOWDOWN_DECLINE_M }) return
        val prompt = SlowdownPrompt(slowdown)
        slowdownPrompt.value = prompt
        viewModelScope.launch {
            delay(SLOWDOWN_PROMPT_MS)
            if (slowdownPrompt.value == prompt) slowdownPrompt.value = null
        }
    }

    /** Whether the trip's traffic already slows the road where the driver is, or a "Bouchon" report lies close by. */
    private fun trafficKnownHere(slowdown: com.eona.app.core.drive.Slowdown): Boolean {
        val known = traffic.value
        val rp = path
        val match = progress()
        if (known != null && rp != null && match != null && known.slowed(match.alongMeters, rp.totalMeters)) return true
        return reports.value.any {
            it.type == ReportType.TrafficJam && Geo.haversine(it.lat, it.lon, slowdown.lat, slowdown.lon) < SLOWDOWN_KNOWN_M
        }
    }

    /**
     * "Oui": a "Bouchon" report there (a guest's quota spared); "Non": the probe is taken back
     * and the driver is not asked again around there for a while.
     */
    fun answerSlowdown(yes: Boolean) {
        val prompt = slowdownPrompt.value ?: return
        slowdownPrompt.value = null
        if (yes) {
            report(ReportDraft(ReportType.TrafficJam, prompted = true))
        } else {
            declinedSlowdowns += Triple(prompt.slowdown.lat, prompt.slowdown.lon, System.currentTimeMillis() + SLOWDOWN_DECLINE_MS)
            viewModelScope.launch { trafficApi.dismissProbe(AccountRepository.token) }
        }
    }

    /** The switch, said (to the end: the new route's first instruction waits) and shown a moment. */
    private fun announceFaster(notice: FasterRouteNotice) {
        fasterNotice.value = notice
        if (AppPreferences.alerts.value.voice) {
            val saved = if (notice.gainMinutes > 1) "${notice.gainMinutes} minutes gagnées" else "1 minute gagnée"
            speaker.speak(
                if (notice.closedRoad) "Route fermée devant : nouvel itinéraire." else "Itinéraire plus rapide trouvé : $saved.",
                AppPreferences.alerts.value.guidanceVolume,
                whole = true,
            )
        }
        viewModelScope.launch {
            delay(FASTER_NOTICE_MS)
            if (fasterNotice.value == notice) fasterNotice.value = null
        }
    }

    /** Where the driver is along the route followed; null off it (or on a simulated trip). */
    private fun progress(): RoutePath.Match? {
        if (ActiveTripRepository.start.value != null) return null
        val fix = LocationRepository.location.value ?: return null
        val match = path?.match(fix.latitude, fix.longitude) ?: return null
        return match.takeIf { it.offRouteMeters <= OFF_ROUTE_M }
    }

    /** Route constraints the driver asked for, as the backend expects them. */
    private fun avoidOptions(): List<String> {
        val s = AppPreferences.settings.value
        return buildList {
            if (s.avoidTolls) add("tolls")
            if (s.avoidHighways) add("highways")
            // "Éviter les bouchons" no longer avoids every reported jam: the faster-route check
            // weighs the time saved instead (considerFasterRoute).
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
                        prompted = draft.prompted,
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
            if (announcedAlerts.add("$id@$step")) speaker.speak(text, AppPreferences.alerts.value.alertVolume)
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
            OverspeedWarning.Voice -> if (prefs.voice) speaker.speak("Vous dépassez la limite de $limitKmh.", prefs.alertVolume)
            OverspeedWarning.Beep -> if (prefs.sound) sounds.play(AlertSound.Overspeed, prefs.vibration, prefs.alertVolume)
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
        sounds.play(if (fresh.any { it.type.isEnforcement }) AlertSound.Detector else AlertSound.Hazard, vibrate, AppPreferences.alerts.value.alertVolume)
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
        /** The route's traffic is asked for again this often during a trip. */
        const val TRAFFIC_REFRESH_MS = 120_000L
        /** No faster route within this long of the last switch; checks this far apart at most. */
        const val FASTER_COOLDOWN_MS = 300_000L
        const val FASTER_RECHECK_MS = 300_000L
        /** The "Itinéraire plus rapide" banner stays this long. */
        const val FASTER_NOTICE_MS = 8_000L
        // "Ralentissement du trafic ?": asked this long; nothing in a trip's first or last metres;
        // not again this close to a "Non" for this long; a "Bouchon" this close is already known.
        const val SLOWDOWN_PROMPT_MS = 10_000L
        /** How long the arrival card stays before going on its own. */
        const val ARRIVAL_MS = 15_000L
        const val SLOWDOWN_TRIP_START_M = 300.0
        const val SLOWDOWN_TRIP_END_M = 500.0
        const val SLOWDOWN_DECLINE_MS = 900_000L
        const val SLOWDOWN_DECLINE_M = 3_000.0
        const val SLOWDOWN_KNOWN_M = 1_000.0
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
        /** After a failed recalculation the wait doubles, up to this. */
        const val RECALC_WAIT_MAX_MS = 60_000L
        /** Driving this far since the last recalculation earns another one. */
        const val RECALC_MIN_MOVE_M = 150.0
        /** Before the route is joined: clearly driving (18 km/h) and this far from the last try. */
        const val RECALC_START_SPEED_MS = 5f
        const val RECALC_START_MOVE_M = 300.0
        /** A route never joined and nobody driving: the trip is dropped after this. */
        const val TRIP_ABANDON_MS = 30 * 60_000L
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
