package com.xradar.app.core.drive

import com.xradar.app.core.geo.Geo
import com.xradar.app.core.model.LocationSample

/** A crawl on a road meant to be fluid, as the app saw it: sent (anonymously) as a probe. */
data class Slowdown(
    val lat: Double,
    val lon: Double,
    /** Course of the traffic concerned (the driver's). */
    val bearingDeg: Double,
    /** The median speed over the window. */
    val speedKmh: Int,
    val limitKmh: Int,
)

/**
 * "Ralentissement du trafic ?": notices a slowdown from the fixes the app already gets, with no
 * timer and no extra GPS — the same rules as iOS (SlowdownDetector.swift, tested there). Only on
 * roads limited to [MIN_LIMIT_KMH] or more (the road's own limit, not a radar's), where lights,
 * crossings and parking do not explain a slow pace. A slowdown is a whole [WINDOW_S] at a median
 * speed under [MAX_SPEED_RATIO] of the limit, still slow in its last [NOW_S], still moving
 * ([MIN_MOVED_M] at least: not parked), with a precise position. After one, nothing for
 * [COOLDOWN_S]. The backend gets the same rules (probeMinLimitKmh, probeMaxSpeedRatio).
 */
class SlowdownDetector {
    private class Fix(val at: Double, val lat: Double, val lon: Double, val kmh: Double, val bearing: Double?)

    private val fixes = ArrayDeque<Fix>()
    private var lastSlowdownAt: Double? = null

    /**
     * One fix: its filtered [speedKmh], and the limit where it is ([limitFromRoad] false for a
     * radar's limit). [paused] (the trip's first or last metres) starts the window again.
     */
    fun update(sample: LocationSample, speedKmh: Int, limitKmh: Int?, limitFromRoad: Boolean, paused: Boolean): Slowdown? {
        val now = sample.timeMs / 1000.0
        if (limitKmh == null || !limitFromRoad || limitKmh < MIN_LIMIT_KMH || paused) {
            fixes.clear()
            return null
        }
        lastSlowdownAt?.let { if (now - it < COOLDOWN_S) return null }
        // A poor fix is left out, without breaking the window.
        sample.accuracyM?.let { if (it > MAX_ACCURACY_M) return null }
        fixes.addLast(Fix(now, sample.latitude, sample.longitude, speedKmh.toDouble(), sample.bearingDeg?.toDouble()))
        while (fixes.isNotEmpty() && now - fixes.first().at > WINDOW_S) fixes.removeFirst()
        val first = fixes.firstOrNull() ?: return null
        if (now - first.at < WINDOW_S - 5 || fixes.size < MIN_FIXES) return null

        // Slow over the window, and still slow now: a jam already left behind is not asked about.
        val slow = MAX_SPEED_RATIO * limitKmh
        val median = median(fixes.map { it.kmh })
        if (median >= slow || median(fixes.filter { now - it.at <= NOW_S }.map { it.kmh }) >= slow) return null
        var moved = 0.0
        for ((a, b) in fixes.zipWithNext()) moved += Geo.haversine(a.lat, a.lon, b.lat, b.lon)
        if (moved < MIN_MOVED_M) return null
        val last = fixes.last()
        // The way the car goes: its last course, else the way it moved over the window.
        val bearing = last.bearing ?: Geo.bearing(first.lat, first.lon, last.lat, last.lon)
        lastSlowdownAt = now
        fixes.clear()
        return Slowdown(last.lat, last.lon, bearing, Math.round(median).toInt(), limitKmh)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]
    }

    companion object {
        const val MIN_LIMIT_KMH = 70
        const val WINDOW_S = 90.0
        /** The window must be this full: a fix every 3 s on average at least. */
        const val MIN_FIXES = 30
        const val MAX_SPEED_RATIO = 0.5
        /** "Still slow now": the last seconds of the window. */
        const val NOW_S = 20.0
        const val MIN_MOVED_M = 150.0
        const val MAX_ACCURACY_M = 30.0
        const val COOLDOWN_S = 300.0
    }
}
