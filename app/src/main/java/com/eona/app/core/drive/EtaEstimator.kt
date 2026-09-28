package com.eona.app.core.drive

import com.eona.app.core.model.Route
import com.eona.app.core.model.RouteTraffic
import com.eona.app.core.model.TrafficStretch
import kotlin.math.abs

/**
 * The dynamic ETA (D2.1, D2.4): time left = the route's base time still ahead + the delays of the
 * jams still ahead, recomputed at each fix without a request. The base is the engine's time,
 * spread along the route by its steps' durations (a motorway kilometre is not a town's); with
 * TomTom's time for the route, that time less the jams TomTom lists, so the rush hour stays in it.
 * A jam counts whole ahead of the driver, pro rata once inside it, not at all behind. The HUD,
 * the shared trip, the group and the trip's measures all read this one. Same rules as the iOS app.
 */
object EtaEstimator {

    /** How the trips record their ETA (D2.5): "dynamic", "proportional" before. */
    const val MODE = "dynamic"

    /**
     * Seconds left on [route] for a driver [alongMeters] along it, [routeMeters] being the app's
     * own length of it; [traffic] measured on that route (its metres scaled to [routeMeters]).
     */
    fun secondsLeft(route: Route, routeMeters: Double, alongMeters: Double, traffic: RouteTraffic? = null): Double {
        if (routeMeters <= 0) return 0.0
        val along = alongMeters.coerceIn(0.0, routeMeters)
        val stretches = traffic?.stretches.orEmpty()
        val scale = traffic?.totalMeters?.takeIf { it > 0 }?.let { routeMeters / it } ?: 1.0
        val listed = stretches.filter { it.source == TrafficStretch.TOMTOM }.sumOf { it.delaySeconds ?: 0 }
        val baseTotal = traffic?.travelSeconds?.takeIf { it > 0 }?.let { (it - listed).coerceAtLeast(0).toDouble() }
            ?: route.durationSeconds.toDouble()
        val delays = stretches.sumOf { delayAhead(it.fromMeters * scale, it.toMeters * scale, it.delaySeconds ?: 0, along) }
        return baseTotal * baseShareLeft(route, routeMeters, along) + delays
    }

    /**
     * The part of the route's base time still ahead [along] metres into it: by its steps' own
     * durations (their metres scaled to [routeMeters]), else by distance. A step with a time but
     * no length (a ferry's wait) counts until the driver reaches it.
     */
    fun baseShareLeft(route: Route, routeMeters: Double, along: Double): Double {
        if (routeMeters <= 0) return 0.0
        val steps = route.steps
        val totalLength = steps.sumOf { it.distanceMeters.toDouble() }
        val totalTime = steps.sumOf { it.durationSeconds.toDouble() }
        if (totalLength <= 0 || totalTime <= 0) return (1 - along / routeMeters).coerceIn(0.0, 1.0)
        val scale = routeMeters / totalLength
        var start = 0.0
        var left = 0.0
        for (step in steps) {
            val length = step.distanceMeters * scale
            val end = start + length
            left += when {
                along <= start -> step.durationSeconds.toDouble()
                along >= end -> 0.0
                else -> step.durationSeconds * (end - along) / length
            }
            start = end
        }
        return (left / totalTime).coerceIn(0.0, 1.0)
    }

    /** What a jam from [from] to [to] metres, costing [delay] s, still costs a driver at [along]. */
    private fun delayAhead(from: Double, to: Double, delay: Int, along: Double): Double = when {
        delay <= 0 || to <= along -> 0.0
        from >= along || to <= from -> delay.toDouble()
        else -> delay * (to - along) / (to - from)
    }
}

/** The arrival shown: it moves only once the ETA moved by a minute or more (D2.4). */
class ArrivalClock {
    private var shownAt: Long? = null

    fun shown(arrivalMillis: Long): Long {
        val current = shownAt
        if (current == null || abs(arrivalMillis - current) >= MOVE_MS) shownAt = arrivalMillis
        return shownAt ?: arrivalMillis
    }

    fun reset() {
        shownAt = null
    }

    private companion object {
        const val MOVE_MS = 60_000L
    }
}
