package com.xradar.app.core.drive

import com.xradar.app.core.geo.Geo
import com.xradar.app.core.model.AlertType
import com.xradar.app.core.model.LocationSample
import com.xradar.app.core.model.RoadAlert
import com.xradar.app.core.model.Route
import com.xradar.app.core.model.TripRecord
import kotlin.math.roundToInt

/**
 * A trip being driven, fed fix by fix: distance, top speed, standstills, the alerts met and the
 * route's estimate. Once the trip is over it makes the history record. Same rules as iOS.
 */
class TripRecorder(toLabel: String, val startedAt: Long = System.currentTimeMillis()) {
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
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    /** The destination changed on the way: the next route gives the new estimate. */
    fun retarget(label: String) {
        toLabel = label
        plannedSeconds = null
    }

    /** The trip's route: the time already driven plus its estimate, once per destination. */
    fun plan(route: Route, now: Long = System.currentTimeMillis()) {
        if (plannedSeconds != null) return
        plannedSeconds = ((now - startedAt) / 1000.0).roundToInt() + route.durationSeconds
    }

    fun add(fix: LocationSample) {
        if (!lastLat.isNaN()) {
            val step = Geo.haversine(lastLat, lastLon, fix.latitude, fix.longitude)
            if (step in STEP_MIN_M..STEP_MAX_M) distanceMeters += step
        }
        lastLat = fix.latitude
        lastLon = fix.longitude
        val kmh = fix.speedKmh.toDouble()
        topSpeedKmh = maxOf(topSpeedKmh, kmh.roundToInt())

        if (kmh < STOPPED_KMH) {
            if (stoppedSince == null) stoppedSince = fix.timeMs
        } else {
            val since = stoppedSince ?: return
            stoppedSince = null
            val still = (fix.timeMs - since) / 1000.0
            if (still >= MIN_STOP_S) {
                stops++
                stoppedSeconds += still
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
     * The history record, or null for a trip too short to keep. A standstill still running at the
     * end (parked at the destination) is not a stop.
     */
    fun record(id: String, now: Long = System.currentTimeMillis()): TripRecord? {
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
        /** An alert counts as met once it is this close. */
        private const val MET_METERS = 300
    }
}
