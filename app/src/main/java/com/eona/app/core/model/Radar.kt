package com.eona.app.core.model

import com.eona.app.core.geo.Geo

/**
 * A fixed radar from the official dataset. Pure model.
 * [code] is the source type (ETF/ETD/ETU speed radars, ETFR red-light…).
 */
data class Radar(
    val id: String,
    val code: String,
    val vma: Int?,
    val lat: Double,
    val lon: Double,
    /** A way drivers said it does not control ("Pas dans mon sens"); null: none, both ways. */
    val quietCourse: Double? = null,
) {
    val isSpeedRadar: Boolean get() = !code.uppercase().startsWith("ETFR")

    val alertType: AlertType get() = if (isSpeedRadar) AlertType.RadarFixed else AlertType.Camera

    val displayTitle: String get() = if (isSpeedRadar) "Radar fixe" else "Radar feu rouge"

    /**
     * Whether it controls the way the driver goes ([heading], their course; null: unknown, it
     * does): quiet within [QUIET_DEG] of the way drivers said it does not control ([quietCourse]).
     */
    fun controls(heading: Double?): Boolean {
        if (heading == null) return true
        return quietCourse?.let { Geo.angularDiff(heading, it) > QUIET_DEG } ?: true
    }

    companion object {
        const val QUIET_DEG = 45.0
    }
}
