package com.eona.app.data.bugs

import com.eona.app.core.drive.TripRecorder
import com.eona.app.core.model.GeoPoint
import kotlin.math.roundToInt

/**
 * What a "Navigation" bug report joins (D7.4): the engine and the map of the route, and the trip
 * being driven or else the last one since the app started; all null without any trip. Same shape
 * as iOS and the backend.
 */
data class BugContext(val engine: String?, val mapVersion: String?, val trip: BugTrip?)

/** The trip a navigation bug report is about. */
data class BugTrip(
    /** True for the trip being driven, false for the last one, over. */
    val inProgress: Boolean,
    val toLabel: String?,
    val startedAt: Long?,
    val departedAt: Long?,
    /** Driven so far, or in all once over. */
    val distanceMeters: Int?,
    val plannedMeters: Int?,
    val destination: GeoPoint?,
    /** The route followed (the last one), whole: [BugApi] thins it before sending. */
    val route: List<GeoPoint>?,
)

/**
 * The trips a navigation bug report can be about, in memory only (never saved): the one being
 * driven, and the last one since the app started. The drive screen says when a trip goes to a
 * destination and when it ends.
 */
object BugTripTrace {
    private class Trace(val recorder: TripRecorder, val destination: GeoPoint)

    @Volatile private var current: Trace? = null
    @Volatile private var last: Trace? = null

    /** [recorder]'s trip goes to [destination]: a new trip, or the one running redirected. */
    fun driving(recorder: TripRecorder, destination: GeoPoint) {
        current = Trace(recorder, destination)
    }

    /** The trip being driven is over: it is the last one now. */
    fun ended() {
        last = current ?: last
        current = null
    }

    /** What a navigation bug report joins now. Read on the main thread, where the trip is fed. */
    fun context(): BugContext {
        val running = current
        val trace = running ?: last ?: return BugContext(engine = null, mapVersion = null, trip = null)
        val recorder = trace.recorder
        val route = recorder.route
        return BugContext(
            engine = route?.engine,
            mapVersion = route?.mapVersion,
            trip = BugTrip(
                inProgress = running != null,
                toLabel = recorder.toLabel,
                startedAt = recorder.startedAt,
                departedAt = recorder.departedAt,
                distanceMeters = recorder.distanceMeters.roundToInt(),
                plannedMeters = recorder.plannedMeters,
                destination = trace.destination,
                route = route?.points,
            ),
        )
    }
}
