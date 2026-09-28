package com.eona.app.core.drive

import com.eona.app.core.geo.Geo
import com.eona.app.core.model.LocationSample

/**
 * A car standing still stays still on screen. At a stop the GPS drifts by metres, turns its course
 * every way and reads speeds that are not there (30 km/h at a red light). Once the speed shown is
 * 0, every fix shows where the car stopped, the last course driven and 0 km/h; the car leaves the
 * stop only when the fixes really go away from it, farther at each one, and the speed says so
 * twice ([SpeedFilter]). A poor fix never starts the car, and a better one moves the stop to
 * where it says. Same rules as the iOS app.
 */
class StandstillFilter {
    private val speed = SpeedFilter()
    /** Where the car stands, while the speed shown is 0. */
    private var stop: LocationSample? = null
    /** How far the last fix was from [stop]: leaving means farther at each fix. */
    private var lastAwayM = 0.0
    /** The last course driven, kept at the stop. */
    private var course: Float? = null

    fun update(sample: LocationSample): LocationSample {
        val at = stop
        val away = at?.let { Geo.haversine(it.latitude, it.longitude, sample.latitude, sample.longitude) } ?: 0.0
        val leaving = at == null || (
            away >= holdM(sample.accuracyM) &&
                away > lastAwayM + MIN_STEP_M &&
                (sample.accuracyM ?: 0f) <= MAX_START_ACCURACY_M
            )
        lastAwayM = away
        val shown = speed.update(
            if (leaving) sample.speedMps?.toDouble() else 0.0,
            if (leaving) sample.speedAccuracyMps?.toDouble() else null,
            sample.timeMs,
        )
        if (shown > 0.0) {
            stop = null
            lastAwayM = 0.0
            sample.bearingDeg?.let { course = it }
            return sample.copy(speedMps = shown.toFloat())
        }
        // Standing: a first fix, or a better one farther than the noise that does not move (the
        // GPS correcting itself, not the car leaving), sets where the car is.
        val held = at?.takeUnless {
            better(sample, it) &&
                (away >= holdM(sample.accuracyM) || accuracy(sample) <= accuracy(it) / 2) &&
                (!leaving || (sample.speedMps ?: 0f) < STILL_MPS)
        }
        if (held == null) {
            stop = sample
            lastAwayM = 0.0
        }
        val shownAt = held ?: sample
        return sample.copy(latitude = shownAt.latitude, longitude = shownAt.longitude, speedMps = 0f, bearingDeg = course)
    }

    fun reset() {
        speed.reset()
        stop = null
        lastAwayM = 0.0
        course = null
    }

    private fun better(fix: LocationSample, than: LocationSample) = accuracy(fix) <= accuracy(than)

    private fun accuracy(fix: LocationSample): Float = fix.accuracyM ?: UNKNOWN_ACCURACY_M

    /** The noise around a stop: the fix's own margin, within bounds. */
    private fun holdM(accuracyM: Float?): Double = (accuracyM ?: UNKNOWN_ACCURACY_M).toDouble().coerceIn(MIN_HOLD_M, MAX_HOLD_M)

    private companion object {
        const val MIN_HOLD_M = 8.0
        const val MAX_HOLD_M = 25.0
        /** Leaving: each fix at least this much farther from the stop than the last one. */
        const val MIN_STEP_M = 1.0
        /** A fix worse than this never starts the car. */
        const val MAX_START_ACCURACY_M = 30f
        const val UNKNOWN_ACCURACY_M = 10f
        /** A fix reading less than this (3,6 km/h) says the car does not move. */
        const val STILL_MPS = 1f
    }
}
