package com.eona.app.core.drive

import kotlin.math.abs
import kotlin.math.max

/**
 * When the ETA asks TomTom again (D2.2): at once for a new route, then on an event — the driver
 * off the plan (the arrival moved by [DRIFT_MS] or [DRIFT_RATIO] of the time left since TomTom's
 * last answer), a TomTom jam passed — or at the latest after a wait that grows with the time
 * left. Never closer than the backend's minGapS (its budget, D3.1) nor [MIN_GAP_MS]. Between
 * those, the drivers' and data.gouv's traffic come every [OTHERS_EVERY_MS] without TomTom.
 * The thresholds are first values, to measure (D2.2). Same rules as the iOS app.
 */
class TrafficRefresh {
    enum class Ask { WithTomtom, WithoutTomtom }

    private var lastAskAt = 0L
    private var lastTomtomAt = 0L
    private var tomtomNotBefore = 0L
    private var predictedArrival: Long? = null
    private var jamEnds: List<Double> = emptyList()

    /** A new route: TomTom is asked at once. */
    fun newRoute() {
        lastTomtomAt = 0L
        tomtomNotBefore = 0L
        predictedArrival = null
        jamEnds = emptyList()
    }

    /**
     * What to ask at [now] for a driver [alongMeters] along the route, the arrival shown being
     * [arrival] (epoch millis; null without a route): null for nothing.
     */
    fun due(now: Long, alongMeters: Double, arrival: Long?): Ask? {
        if (now - lastAskAt < DEBOUNCE_MS) return null
        if (now >= tomtomNotBefore && tomtomDue(now, alongMeters, arrival)) return Ask.WithTomtom
        return if (now - lastAskAt >= OTHERS_EVERY_MS) Ask.WithoutTomtom else null
    }

    private fun tomtomDue(now: Long, alongMeters: Double, arrival: Long?): Boolean {
        if (lastTomtomAt == 0L) return true
        val left = arrival?.let { it - now } ?: return false
        val since = now - lastTomtomAt
        val drifted = predictedArrival?.let { abs(arrival - it) >= max(DRIFT_MS, (left * DRIFT_RATIO).toLong()) } == true
        val jamPassed = jamEnds.any { it <= alongMeters }
        return drifted || jamPassed || since >= maxWaitMs(left)
    }

    fun asked(now: Long) {
        lastAskAt = now
    }

    /**
     * The answer came: [tomtom] whether TomTom timed the route, [arrival] the arrival shown with
     * it, [tomtomJamEnds] where its jams end (metres along the route), [minGapSeconds] the backend's
     * least wait before the next recalage.
     */
    fun answered(now: Long, tomtom: Boolean, asked: Ask, arrival: Long?, tomtomJamEnds: List<Double>, minGapSeconds: Int) {
        val gap = max(MIN_GAP_MS, minGapSeconds * 1000L)
        if (tomtom) {
            lastTomtomAt = now
            predictedArrival = arrival
            jamEnds = tomtomJamEnds
        }
        if (tomtom || asked == Ask.WithTomtom) tomtomNotBefore = now + gap
    }

    private fun maxWaitMs(leftMs: Long): Long = when {
        leftMs >= 60 * MINUTE -> 15 * MINUTE
        leftMs >= 20 * MINUTE -> 10 * MINUTE
        else -> 5 * MINUTE
    }

    private companion object {
        const val MINUTE = 60_000L
        const val DEBOUNCE_MS = 15_000L
        const val MIN_GAP_MS = 60_000L
        const val OTHERS_EVERY_MS = 120_000L
        const val DRIFT_MS = 120_000L
        const val DRIFT_RATIO = 0.1
    }
}
