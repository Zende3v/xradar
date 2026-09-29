package com.eona.app.core.drive

import com.eona.app.core.geo.Geo
import com.eona.app.core.model.AlertType
import com.eona.app.core.model.EtaCheck
import com.eona.app.core.model.LocationSample
import com.eona.app.core.model.RoadAlert
import com.eona.app.core.model.Route
import com.eona.app.core.model.TripMeasure
import com.eona.app.core.model.TripRecord
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The traffic where the car stands still, as the drive screen knows it (D1.4): a long stop in a jam
 * is part of the drive, one on a clear road is a pause no ETA can foresee.
 */
enum class StopTraffic {
    /** On a slowed stretch of the route, or a drivers' jam reported close by. */
    Jam,
    /** On the route, its traffic known, and clear here. */
    Clear,
    /** No traffic known yet, off the route, or no route. */
    Unknown,
}

/**
 * A trip being driven, fed fix by fix: distance, top speed, standstills, the alerts met and the
 * route's estimate. Once the trip is over it makes the history record. Same rules as iOS.
 *
 * It also measures the ETA and the routes (D1.7): the real departure, the ETA shown at 0, 25, 50
 * and 75 % of the way, the long stops split into pauses and uncertain ones, and the routes and
 * traffic used. [platform] and [appVersion] say who recorded it.
 */
class TripRecorder(
    toLabel: String,
    private val platform: String,
    private val appVersion: String,
    val startedAt: Long = System.currentTimeMillis(),
) {
    var toLabel: String = toLabel
        private set
    var distanceMeters: Double = 0.0
        private set
    private var topSpeedKmh = 0
    private var plannedSeconds: Int? = null
    private var stops = 0
    private var stoppedSeconds = 0.0
    private var stoppedSince: Long? = null
    /** Alerts met, by key, with their kind. */
    private val met = HashMap<String, AlertType>()

    /** How many alerts the trip has met, for the arrival card. */
    val alertsMet: Int get() = met.size
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    /** When the driver first joined the route (epoch millis); null before. */
    var departedAt: Long? = null
        private set
    /** The distance of the route in force at the departure. */
    var plannedMeters: Int? = null
        private set
    /** The last route the trip followed. */
    var route: Route? = null
        private set
    private var departedMeters = 0.0
    private var manualStart = false
    private var retargeted = false
    private var mapVersion: String? = null
    private var pausedSeconds = 0.0
    private var uncertainSeconds = 0.0
    // The stop running: one of its fixes was in a jam; all of them were on a clear road.
    private var stopInJam = false
    private var stopAllClear = true
    private val etaChecks = ArrayList<EtaCheck>()
    /** How many of [CHECKPOINTS] are passed. */
    private var checkpointsPassed = 0
    private var recalcCount = 0
    private var fasterCount = 0
    private val engines = LinkedHashSet<String>()
    private val trafficSources = LinkedHashSet<String>()

    /** Departed, and an ETA checkpoint still to come: [checkpoint] wants the next fixes. */
    val awaitsCheckpoint: Boolean get() = departedAt != null && checkpointsPassed < CHECKPOINTS.size

    /** The destination changed on the way: the next route gives the new estimate. */
    fun retarget(label: String) {
        retargeted = true
        toLabel = label
        plannedSeconds = null
    }

    /** The trip's route: the time already driven plus its estimate, once per destination. */
    fun plan(route: Route, now: Long = System.currentTimeMillis()) {
        if (plannedSeconds != null) return
        plannedSeconds = ((now - startedAt) / 1000.0).roundToInt() + route.durationSeconds
    }

    /** A route the trip follows (the first one, a recalculation, a faster one): its engine counts. */
    fun follow(route: Route) {
        this.route = route
        engines.add(route.engine ?: UNKNOWN_ENGINE)
    }

    /** Leaving the route gave a new one. */
    fun recalculated() {
        recalcCount++
    }

    /** The trip switched to a faster route. */
    fun tookFaster() {
        fasterCount++
    }

    /** The route's traffic came from these sources ("tomtom", "crowd"). */
    fun sawTraffic(sources: Collection<String>) {
        trafficSources.addAll(sources)
    }

    /**
     * The real departure, once per trip: the driver joins the route (a trip started by hand,
     * [manualStart]: its first fix). [route], then in force, gives the planned distance and the
     * map, and the arrival the dock shows ([arrivalAt]) is the 0 % checkpoint; none without a
     * route yet.
     */
    fun depart(route: Route?, arrivalAt: Long?, manualStart: Boolean, both: EtaPair? = null, now: Long = System.currentTimeMillis()) {
        if (departedAt != null) return
        departedAt = now
        departedMeters = distanceMeters
        this.manualStart = manualStart
        plannedMeters = route?.distanceMeters
        mapVersion = route?.mapVersion
        if (route != null && arrivalAt != null) keepEta(0, arrivalAt, both, now)
    }

    /**
     * After the departure, at each fix: the arrival the dock shows now ([arrivalAt]) is kept the
     * first time the trip is 25, 50 and 75 % done, each once (all those passed at once on a jump).
     * Done: the metres driven since the departure, over those plus the metres of [route] left
     * ([remainingShare] of it).
     */
    fun checkpoint(route: Route, remainingShare: Double, arrivalAt: Long, both: EtaPair? = null, now: Long = System.currentTimeMillis()) {
        if (!awaitsCheckpoint) return
        val driven = distanceMeters - departedMeters
        val total = driven + route.distanceMeters * remainingShare.coerceIn(0.0, 1.0)
        if (total <= 0) return
        val done = driven / total
        while (checkpointsPassed < CHECKPOINTS.size && done >= CHECKPOINTS[checkpointsPassed] / 100.0) {
            keepEta(CHECKPOINTS[checkpointsPassed], arrivalAt, both, now)
            checkpointsPassed++
        }
    }

    /** The arrival the dock announces now, and the pauses and uncertain stops already over. */
    private fun keepEta(at: Int, arrivalAt: Long, both: EtaPair?, now: Long) {
        etaChecks += EtaCheck(
            at = at,
            shownAt = now,
            arrivalAt = arrivalAt,
            withDatagouvAt = both?.withDatagouvAt,
            withoutDatagouvAt = both?.withoutDatagouvAt,
            pausedBefore = pausedSeconds.roundToInt(),
            uncertainBefore = uncertainSeconds.roundToInt(),
        )
    }

    /** [trafficHere], the traffic where the car is, is asked only while it stands still. */
    fun add(fix: LocationSample, trafficHere: () -> StopTraffic) {
        if (!lastLat.isNaN()) {
            val step = Geo.haversine(lastLat, lastLon, fix.latitude, fix.longitude)
            if (step in STEP_MIN_M..STEP_MAX_M) distanceMeters += step
        }
        lastLat = fix.latitude
        lastLon = fix.longitude
        val kmh = fix.speedKmh.toDouble()
        topSpeedKmh = maxOf(topSpeedKmh, kmh.roundToInt())

        if (kmh < STOPPED_KMH) {
            if (stoppedSince == null) {
                stoppedSince = fix.timeMs
                stopInJam = false
                stopAllClear = true
            }
            when (trafficHere()) {
                StopTraffic.Jam -> {
                    stopInJam = true
                    stopAllClear = false
                }
                StopTraffic.Clear -> Unit
                StopTraffic.Unknown -> stopAllClear = false
            }
        } else {
            val since = stoppedSince ?: return
            stoppedSince = null
            val still = (fix.timeMs - since) / 1000.0
            if (still >= MIN_STOP_S) {
                stops++
                stoppedSeconds += still
            }
            // A long stop in a jam is part of the drive; on a road known clear all along it is a
            // pause, otherwise it stays uncertain.
            if (still >= LONG_STOP_S && !stopInJam) {
                if (stopAllClear) pausedSeconds += still else uncertainSeconds += still
            }
        }
    }

    /** The alerts on the HUD now: each one reached counts once. */
    fun meet(alerts: List<RoadAlert>) {
        for (alert in alerts) {
            if (alert.distanceMeters <= MET_METERS) met.putIfAbsent(alert.id ?: "${alert.type.name}:${alert.title}", alert.type)
        }
    }

    /**
     * The history record, or null for a trip too short to keep. [arrived]: it ended at the
     * destination. A standstill still running at the end (parked at the destination) is not a stop.
     */
    fun record(id: String, arrived: Boolean, now: Long = System.currentTimeMillis()): TripRecord? {
        val duration = (now - startedAt) / 1000.0
        if (distanceMeters < MIN_METERS || duration < MIN_SECONDS) return null
        return TripRecord(
            id = id,
            startedAt = startedAt,
            fromLabel = "Ma position",
            toLabel = toLabel,
            distanceMeters = distanceMeters.roundToInt(),
            durationSeconds = duration.toInt(),
            alertsCount = met.size,
            topSpeedKmh = topSpeedKmh,
            plannedSeconds = plannedSeconds,
            stops = stops,
            stoppedSeconds = stoppedSeconds.roundToInt(),
            events = met.values.groupingBy { it }.eachCount(),
            measure = TripMeasure(
                arrived = arrived,
                departedAt = departedAt,
                manualStart = manualStart,
                retargeted = retargeted,
                plannedMeters = plannedMeters,
                pausedSeconds = pausedSeconds.roundToInt(),
                uncertainSeconds = uncertainSeconds.roundToInt(),
                etaChecks = etaChecks.toList(),
                recalcCount = recalcCount,
                fasterCount = fasterCount,
                engines = engines.toList(),
                mapVersion = mapVersion,
                appVersion = appVersion,
                platform = platform,
                etaMode = ETA_MODE,
                trafficSources = trafficSources.toList(),
            ),
        )
    }

    companion object {
        /** Shorter trips are not kept. */
        const val MIN_METERS = 500.0
        const val MIN_SECONDS = 60.0
        /** Jumps outside this are GPS noise or a gap, not driving. */
        private const val STEP_MIN_M = 1.0
        private const val STEP_MAX_M = 250.0
        /** Slower than this is standing still (km/h). */
        private const val STOPPED_KMH = 3.0
        /** A standstill counts as a stop from this long: a red light, a queue at a standstill. */
        private const val MIN_STOP_S = 10.0
        /** From this long, a stop outside a jam is a pause (D1.4). */
        private const val LONG_STOP_S = 300.0
        /** An alert counts as met once it is this close. */
        private const val MET_METERS = 300
        /** After the departure (0 %), the ETA is kept at these percentages of the way. */
        private val CHECKPOINTS = intArrayOf(25, 50, 75)
        /** The dock's ETA: the route's time pro rata of what is left of it (TripProgress). */
        const val ETA_MODE = EtaEstimator.MODE
        /** The engine of a route that does not say. */
        private const val UNKNOWN_ENGINE = "unknown"
    }
}

/** The two arrivals of D2.6, with and without data.gouv's traffic, for the trip's measures. */
data class EtaPair(val withDatagouvAt: Long, val withoutDatagouvAt: Long)
