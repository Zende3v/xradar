package com.xradar.app.feature.drive

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xradar.app.core.geo.Geo
import com.xradar.app.core.geo.GuidanceText
import com.xradar.app.core.model.GeoPoint
import com.xradar.app.core.model.GpsSignal
import com.xradar.app.core.model.GuidanceInstruction
import com.xradar.app.core.model.LocationSample
import com.xradar.app.core.model.Place
import com.xradar.app.core.model.Radar
import com.xradar.app.core.model.RadarZone
import com.xradar.app.core.model.ReportType
import com.xradar.app.core.model.RoadAlert
import com.xradar.app.core.model.Route
import com.xradar.app.core.model.TripInfo
import com.xradar.app.core.model.TripRecord
import com.xradar.app.core.model.UserReport
import com.xradar.app.data.account.AccountRepository
import com.xradar.app.data.preferences.AlertPreferences
import com.xradar.app.data.preferences.AppPreferences
import com.xradar.app.data.radar.RadarRepository
import com.xradar.app.data.reports.NewReport
import com.xradar.app.data.reports.ReportsRepository
import com.xradar.app.data.routing.ActiveTripRepository
import com.xradar.app.data.routing.RoutingRepository
import com.xradar.app.data.stats.TripHistoryRepository
import com.xradar.app.location.LocationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
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
    private val liveApi = com.xradar.app.data.live.LiveApi()
    private val tripHistory = TripHistoryRepository(application)
    private val radars = MutableStateFlow<List<Radar>>(emptyList())
    private val reports = MutableStateFlow<List<UserReport>>(emptyList())
    private val zones = MutableStateFlow<List<RadarZone>>(emptyList())
    private val liveUsers = MutableStateFlow<List<com.xradar.app.core.model.LiveUser>>(emptyList())
    private val guidance = MutableStateFlow<GuidanceInstruction?>(null)
    private val speaker = GuidanceSpeaker(application)

    /** Radars + reports + radar-car zones, pre-combined so the main combine stays ≤5 flows. */
    private val roadObjects = combine(radars, reports, zones) { r, rep, z -> Triple(r, rep, z) }

    private var lastFetchLat = Double.NaN
    private var lastFetchLon = Double.NaN
    private var lastRecalcAt = 0L

    // Turn-by-turn cursor over the active route's steps.
    private var stepIndex = 1
    private var announcedFar = false
    private var announcedNear = false

    // Live accumulation of the trip in progress (saved locally when it ends).
    private var tripActive = false
    private var tripStartedAt = 0L
    private var tripToLabel: String? = null
    private var tripDistanceM = 0.0
    private var tripTopSpeed = 0
    private var tripAlerts = 0
    private var lastTripLat = Double.NaN
    private var lastTripLon = Double.NaN

    val uiState: StateFlow<DriveUiState> = combine(
        LocationRepository.location,
        LocationRepository.signal,
        roadObjects,
        ActiveTripRepository.route,
        AppPreferences.alerts,
    ) { sample, signal, objects, route, prefs ->
        val (radarList, reportList, zoneList) = objects
        // Only keep radar/report types the user enabled in the Options (E1) menu.
        val enabledRadars = radarList.filter {
            (it.isSpeedRadar && prefs.radarFixed) || (!it.isSpeedRadar && prefs.cameras)
        }
        val enabledReports = reportList.filter { reportEnabled(it.type, prefs) }
        val speedKmh = when (signal) {
            GpsSignal.Searching, GpsSignal.Lost -> 0
            else -> (sample?.speedKmh ?: 0f).roundToInt().coerceAtLeast(0)
        }
        val (radarAlert, limit) = relevantAheadOf(enabledRadars, sample, speedKmh)
        val reportAlert = nearestReportAhead(enabledReports, sample, speedKmh)
        val alert = listOfNotNull(radarAlert, reportAlert).minByOrNull { it.distanceMeters }
        DriveUiState(
            speedKmh = speedKmh,
            speedLimitKmh = limit,
            trip = tripFrom(route),
            alert = alert,
            gpsSignal = signal,
            location = sample,
            radars = enabledRadars,
            reports = enabledReports,
            zones = zoneList,
            routePoints = route?.points ?: emptyList(),
        )
    }.combine(guidance) { state, instruction ->
        state.copy(guidance = instruction)
    }.combine(liveUsers) { state, users ->
        state.copy(liveUsers = users)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DriveUiState(0, null, null, null, GpsSignal.Searching),
    )

    init {
        // Refetch radars (and reports) around the driver as they move.
        viewModelScope.launch {
            LocationRepository.location.collect { sample ->
                if (sample != null && shouldFetch(sample)) {
                    lastFetchLat = sample.latitude
                    lastFetchLon = sample.longitude
                    radars.value = radarRepository.near(sample.latitude, sample.longitude, radiusM())
                    refreshReports(sample.latitude, sample.longitude)
                }
            }
        }
        // Re-fetch immediately when the loading radius changes in Settings.
        viewModelScope.launch {
            AppPreferences.alerts.map { it.alertRadiusKm }.distinctUntilChanged().collect {
                val fix = LocationRepository.location.value ?: return@collect
                radars.value = radarRepository.near(fix.latitude, fix.longitude, radiusM())
                refreshReports(fix.latitude, fix.longitude)
            }
        }
        // Reports are time-sensitive: refresh them on a short interval too.
        viewModelScope.launch {
            while (true) {
                LocationRepository.location.value?.let { fix -> refreshReports(fix.latitude, fix.longitude) }
                delay(REPORT_REFRESH_MS)
            }
        }
        // Live users: share my position (unless invisible) and fetch nearby drivers.
        viewModelScope.launch {
            while (true) {
                val token = AccountRepository.token
                val fix = LocationRepository.location.value
                val prefs = AppPreferences.alerts.value
                if (token != null && fix != null) {
                    liveApi.share(token, fix.latitude, fix.longitude, fix.bearingDeg, (fix.speedKmh ?: 0f).roundToInt(), prefs.liveVisible)
                    liveUsers.value = liveApi.near(token, fix.latitude, fix.longitude, prefs.liveRadiusKm * 1000)
                } else {
                    liveUsers.value = emptyList()
                }
                delay(LIVE_REFRESH_MS)
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
                val from = LocationRepository.location.value ?: return@collect
                val route = routingRepository.route(
                    GeoPoint(from.latitude, from.longitude),
                    GeoPoint(destination.lat, destination.lon),
                )
                ActiveTripRepository.setRoute(route)
            }
        }
        // Accumulate distance/top-speed/arrival while a trip is active.
        viewModelScope.launch {
            LocationRepository.location.collect { sample ->
                if (!tripActive || sample == null) return@collect
                if (!lastTripLat.isNaN()) {
                    val step = Geo.haversine(lastTripLat, lastTripLon, sample.latitude, sample.longitude)
                    if (step in TRIP_MIN_STEP_M..TRIP_MAX_STEP_M) tripDistanceM += step
                }
                lastTripLat = sample.latitude
                lastTripLon = sample.longitude
                val kmh = (sample.speedKmh ?: 0f).roundToInt()
                if (kmh > tripTopSpeed) tripTopSpeed = kmh
                // Auto-finish when we reach the destination.
                ActiveTripRepository.destination.value?.let { dest ->
                    val toDest = Geo.haversine(sample.latitude, sample.longitude, dest.lat, dest.lon)
                    if (toDest < ARRIVE_M && tripDistanceM >= MIN_TRIP_M) ActiveTripRepository.clear()
                }
            }
        }
        // Count distinct alert encounters during the trip.
        viewModelScope.launch {
            uiState.map { it.alert != null }.distinctUntilChanged().collect { present ->
                if (present && tripActive) tripAlerts++
            }
        }
        // Voice announcements for radars/reports (distance steps) + overspeed.
        viewModelScope.launch {
            uiState.collect { s ->
                if (!AppPreferences.alerts.value.voice) return@collect
                announceAlert(s.alert)
                announceOverspeed(s.speedKmh, s.speedLimitKmh)
            }
        }
        // Recompute the route if the driver leaves it (off-route detection).
        viewModelScope.launch {
            LocationRepository.location.collect { sample ->
                val destination = ActiveTripRepository.destination.value ?: return@collect
                val route = ActiveTripRepository.route.value ?: return@collect
                if (sample == null) return@collect
                val offBy = route.points.minOfOrNull {
                    Geo.haversine(sample.latitude, sample.longitude, it.lat, it.lon)
                } ?: return@collect
                val now = System.currentTimeMillis()
                if (offBy > OFF_ROUTE_M && now - lastRecalcAt > RECALC_COOLDOWN_MS) {
                    lastRecalcAt = now
                    routingRepository.route(
                        GeoPoint(sample.latitude, sample.longitude),
                        GeoPoint(destination.lat, destination.lon),
                    )?.let { ActiveTripRepository.setRoute(it) }
                }
            }
        }
        // Reset the turn-by-turn cursor whenever the route changes (new trip or recalc).
        viewModelScope.launch {
            ActiveTripRepository.route.collect { route ->
                stepIndex = 1
                announcedFar = false
                announcedNear = false
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

    /** Advance the step cursor to the maneuver ahead, emit it, and voice cues at thresholds. */
    private fun updateGuidance(sample: LocationSample?, route: Route?) {
        val steps = route?.steps ?: emptyList()
        if (sample == null || steps.size < 2) {
            guidance.value = null
            return
        }
        if (stepIndex >= steps.size) stepIndex = steps.size - 1

        // Skip forward past any maneuver we've reached (or already driven through).
        while (stepIndex < steps.size - 1) {
            val cur = steps[stepIndex]
            val curDist = Geo.haversine(sample.latitude, sample.longitude, cur.location.lat, cur.location.lon)
            val next = steps[stepIndex + 1]
            val nextDist = Geo.haversine(sample.latitude, sample.longitude, next.location.lat, next.location.lon)
            if (curDist < STEP_REACHED_M || nextDist < curDist) {
                stepIndex++
                announcedFar = false
                announcedNear = false
            } else {
                break
            }
        }

        val target = steps[stepIndex]
        val meters = Geo.haversine(
            sample.latitude, sample.longitude, target.location.lat, target.location.lon,
        ).roundToInt()

        guidance.value = GuidanceInstruction(
            maneuver = GuidanceText.maneuverOf(target),
            distanceMeters = meters,
            primaryText = GuidanceText.verb(target),
            roadName = target.name.ifBlank { null },
        )

        if (!AppPreferences.alerts.value.voice) return
        // Heads-up ~12 s ahead (clamped), then a short cue at the maneuver.
        val speedMs = ((sample.speedKmh ?: 0f) * 1000f / 3600f)
        val farThreshold = (speedMs * 12f).toDouble().coerceIn(FAR_MIN_M, FAR_MAX_M)
        if (!announcedFar && meters <= farThreshold && meters > NEAR_ANNOUNCE_M) {
            announcedFar = true
            speaker.speak(GuidanceText.spokenFar(target, meters))
        }
        if (!announcedNear && meters <= NEAR_ANNOUNCE_M) {
            announcedNear = true
            speaker.speak(GuidanceText.spokenNear(target))
        }
    }

    override fun onCleared() {
        speaker.shutdown()
        super.onCleared()
    }

    private fun startTrip(destination: Place) {
        tripToLabel = destination.name
        if (tripActive) return
        tripActive = true
        tripStartedAt = System.currentTimeMillis()
        tripDistanceM = 0.0
        tripTopSpeed = 0
        tripAlerts = 0
        lastTripLat = Double.NaN
        lastTripLon = Double.NaN
    }

    /** Save the finished trip locally if it's worth keeping. */
    private fun finalizeTrip() {
        if (!tripActive) return
        val durationS = ((System.currentTimeMillis() - tripStartedAt) / 1000).toInt()
        val distanceM = tripDistanceM.roundToInt()
        if (distanceM >= MIN_TRIP_M && durationS >= MIN_TRIP_S) {
            tripHistory.add(
                TripRecord(
                    id = UUID.randomUUID().toString(),
                    startedAt = tripStartedAt,
                    fromLabel = "Ma position",
                    toLabel = tripToLabel ?: "Destination",
                    distanceMeters = distanceM,
                    durationSeconds = durationS,
                    alertsCount = tripAlerts,
                    topSpeedKmh = tripTopSpeed,
                ),
            )
        }
        tripActive = false
        tripToLabel = null
    }

    /** Loading radius (metres) from the user's setting (20..1000 km). */
    private fun radiusM(): Int = AppPreferences.alerts.value.alertRadiusKm * 1000

    private suspend fun refreshReports(lat: Double, lon: Double) {
        val near = reportsRepository.near(lat, lon, radiusM())
        reports.value = near.reports
        zones.value = near.zones
    }

    /**
     * Post a crowdsourced report at the current position (optimistically shown).
     * Extra fields ([plate] for a radar car, [street]/[side] for a camera) are
     * validated server-side against the account's role.
     */
    fun report(type: ReportType, plate: String? = null, street: String? = null, side: String? = null) {
        val fix = LocationRepository.location.value ?: return
        viewModelScope.launch {
            val created = reportsRepository.create(
                NewReport(type, fix.latitude, fix.longitude, plate = plate, street = street, side = side),
                AccountRepository.token,
                AccountRepository.deviceId,
            )
            // Radar cars appear as zones, not points → refetch to get the new zone.
            if (created != null && type != ReportType.VoitureRadar) {
                reports.value = reports.value + created
            }
            refreshReports(fix.latitude, fix.longitude)
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

    /** Community vote on a report ("toujours là" / "plus là"). */
    fun vote(reportId: String, confirm: Boolean) {
        viewModelScope.launch {
            reportsRepository.vote(reportId, confirm)
            if (!confirm) reports.value = reports.value.filterNot { it.id == reportId }
            LocationRepository.location.value?.let { fix -> refreshReports(fix.latitude, fix.longitude) }
        }
    }

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

    /** Speak once (then cool down) when clearly over the active limit. */
    private fun announceOverspeed(speedKmh: Int, limitKmh: Int?) {
        if (limitKmh == null || limitKmh <= 0) return
        if (speedKmh <= limitKmh + OVERSPEED_MARGIN) return
        val now = System.currentTimeMillis()
        if (now - lastOverspeedAt < OVERSPEED_COOLDOWN_MS) return
        lastOverspeedAt = now
        speaker.speak("Vous dépassez la limite de $limitKmh.")
    }

    private fun reportEnabled(type: ReportType, prefs: AlertPreferences): Boolean = when (type) {
        ReportType.RadarMobile -> prefs.radarMobile
        ReportType.Camera -> prefs.cameras
        ReportType.ControlZone -> prefs.controlZones
        ReportType.Accident, ReportType.Hazard -> prefs.hazards
        ReportType.VoitureRadar -> true
    }

    /** Nearest report ahead within alert range → a [RoadAlert]. */
    private fun nearestReportAhead(
        reportList: List<UserReport>,
        sample: LocationSample?,
        speedKmh: Int,
    ): RoadAlert? {
        if (sample == null || reportList.isEmpty()) return null
        val heading = sample.bearingDeg?.toDouble()
        val hit = reportList
            .map { report -> report to Geo.haversine(sample.latitude, sample.longitude, report.lat, report.lon) }
            .filter { (report, _) ->
                heading == null || Geo.angularDiff(
                    heading,
                    Geo.bearing(sample.latitude, sample.longitude, report.lat, report.lon),
                ) <= AHEAD_CONE_DEG
            }
            .sortedBy { it.second }
            .firstOrNull { it.second <= ALERT_DISTANCE_M }
            ?: return null
        val (report, distance) = hit
        val metersPerSecond = (speedKmh * 1000f / 3600f).coerceAtLeast(1f)
        return RoadAlert(
            type = report.type.alertType,
            title = report.type.label,
            roadLabel = report.sideLabel?.let { "côté $it" },
            speedLimitKmh = null,
            distanceMeters = distance.roundToInt(),
            etaSeconds = (distance / metersPerSecond).roundToInt(),
            confidence = report.confidence,
            lastReportedLabel = report.ageLabel,
            id = report.id,
        )
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

    /** Nearest radar ahead → an alert; nearest speed radar ahead → the active limit. */
    private fun relevantAheadOf(
        radarList: List<Radar>,
        sample: LocationSample?,
        speedKmh: Int,
    ): Pair<RoadAlert?, Int?> {
        if (sample == null || radarList.isEmpty()) return null to null
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

        val alert = ahead.firstOrNull()
            ?.takeIf { it.second <= ALERT_DISTANCE_M }
            ?.let { (radar, distance) ->
                val metersPerSecond = (speedKmh * 1000f / 3600f).coerceAtLeast(1f)
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

        return alert to limit
    }

    private companion object {
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        const val STOP_TIMEOUT_MS = 5_000L
        // Loading radius comes from the user setting; refetch every few km of travel.
        const val FETCH_MOVE_M = 3000.0
        const val REPORT_REFRESH_MS = 25_000L
        const val LIVE_REFRESH_MS = 8_000L
        // Trip recording.
        const val ARRIVE_M = 45.0
        const val MIN_TRIP_M = 500
        const val MIN_TRIP_S = 60
        const val TRIP_MIN_STEP_M = 1.0
        const val TRIP_MAX_STEP_M = 250.0
        const val ALERT_DISTANCE_M = 1500.0
        // The VMA sign shows while a speed radar is the active alert ahead. (Road-wide
        // limits everywhere need an OSM maxspeed source — planned separately.)
        const val LIMIT_DISTANCE_M = 1000.0
        const val AHEAD_CONE_DEG = 75.0
        const val OFF_ROUTE_M = 45.0
        const val RECALC_COOLDOWN_MS = 2_500L
        // Turn-by-turn thresholds.
        const val STEP_REACHED_M = 25.0
        const val NEAR_ANNOUNCE_M = 45
        const val FAR_MIN_M = 150.0
        const val FAR_MAX_M = 800.0
        // Alert voice.
        const val VOICE_FAR_M = 500
        const val VOICE_NEAR_M = 200
        const val OVERSPEED_MARGIN = 5
        const val OVERSPEED_COOLDOWN_MS = 20_000L
    }
}
