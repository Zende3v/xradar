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
    /** The way it controls, in degrees (official site, on its road); null: unknown, both ways. */
    val course: Double? = null,
    /** A way drivers said it does not control ("Pas dans mon sens"); null: none. */
    val quietCourse: Double? = null,
) {
    val isSpeedRadar: Boolean get() = !code.uppercase().startsWith("ETFR")

    val alertType: AlertType get() = if (isSpeedRadar) AlertType.RadarFixed else AlertType.Camera

    val displayTitle: String get() = if (isSpeedRadar) "Radar fixe" else "Radar feu rouge"

    /**
     * Whether it controls the way the driver goes ([heading], their course; null: unknown, it
     * does). The drivers' word wins ([quietCourse]); else the official way ([course]): more than
     * [OTHER_WAY_DEG] off it, the driver is on the other side. Unknown: both ways, as before.
     */
    fun controls(heading: Double?): Boolean {
        if (heading == null) return true
        quietCourse?.let { return Geo.angularDiff(heading, it) > QUIET_DEG }
        course?.let { return Geo.angularDiff(heading, it) <= OTHER_WAY_DEG }
        return true
    }

    companion object {
        const val QUIET_DEG = 45.0
        const val OTHER_WAY_DEG = 120.0
    }
}
