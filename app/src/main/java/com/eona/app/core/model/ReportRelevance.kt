package com.eona.app.core.model

import kotlin.math.abs
import kotlin.math.max

/** How much a report is worth to *this* driver, right now. */
enum class Relevance { Gone, Low, Normal, High }

/**
 * The driver-facing half of the report score. The backend keeps the intrinsic part —
 * time decay, confirmations, contradictions — and this multiplies it by what depends
 * on who is asking:
 *
 *     final = intrinsic × road × direction × distance
 *
 * Pure Kotlin (no Android types), so the rule travels with the model to iOS.
 */
object ReportRelevance {

    const val MINIMUM = 10.0
    private const val LOW = 10.0
    private const val NORMAL = 30.0
    private const val HIGH = 60.0

    private const val ROAD_SAME = 1.0
    private const val ROAD_OTHER = 0.35
    private const val DIRECTION_SAME = 1.0
    private const val DIRECTION_UNKNOWN = 0.75
    private const val DIRECTION_OPPOSITE = 0.15

    /** Beyond this angle the two courses are opposite; under it, the same way. */
    private const val SAME_WAY_DEG = 60.0
    private const val OPPOSITE_WAY_DEG = 120.0

    /**
     * [distanceMeters] from the driver, [driverBearing] their course (null = unknown),
     * [onSameRoad] false when the report sits off the road being driven.
     */
    fun score(
        report: UserReport,
        distanceMeters: Double,
        driverBearing: Double?,
        onSameRoad: Boolean = true,
    ): Double {
        val distance = max(0.0, 1.0 - distanceMeters / report.impactMeters)
        if (distance <= 0.0) return 0.0
        val road = if (onSameRoad) ROAD_SAME else ROAD_OTHER
        return report.score * road * directionFactor(report, driverBearing) * distance
    }

    /**
     * The carriageway the report is on, compared with where the driver is heading.
     * The reporter's course plus their answer ("mon sens" / "sens opposé") gives the
     * absolute direction of the event; without a course, nobody can tell.
     */
    fun directionFactor(report: UserReport, driverBearing: Double?): Double {
        val reported = report.bearingDeg ?: return DIRECTION_UNKNOWN
        val driver = driverBearing ?: return DIRECTION_UNKNOWN
        val eventWay = if (report.direction == "opposite") reported + 180.0 else reported
        val delta = angleBetween(eventWay, driver)
        return when {
            delta <= SAME_WAY_DEG -> DIRECTION_SAME
            delta >= OPPOSITE_WAY_DEG -> DIRECTION_OPPOSITE
            else -> DIRECTION_UNKNOWN
        }
    }

    /** Band the app shows: under the minimum the backend has already dropped it. */
    fun bandOf(score: Double): Relevance = when {
        score < LOW -> Relevance.Gone
        score < NORMAL -> Relevance.Low
        score < HIGH -> Relevance.Normal
        else -> Relevance.High
    }

    fun label(band: Relevance): String = when (band) {
        Relevance.Gone -> "Périmé"
        Relevance.Low -> "Pertinence faible"
        Relevance.Normal -> "Pertinence normale"
        Relevance.High -> "Forte pertinence"
    }

    /** Smallest angle between two courses, in degrees (0..180). */
    private fun angleBetween(a: Double, b: Double): Double {
        val d = abs((a - b) % 360.0)
        return if (d > 180.0) 360.0 - d else d
    }
}
