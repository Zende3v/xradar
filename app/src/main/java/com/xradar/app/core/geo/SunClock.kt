package com.xradar.app.core.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Is the sun up here, right now? Used to pick the day or night basemap without
 * asking the phone's theme. Low-precision solar position (±0.5°, from the standard
 * astronomical almanac approximation) — far more accuracy than a map needs.
 * Pure Kotlin, no Android types, so it moves to KMP with the rest.
 */
object SunClock {

    /** Sun altitude in degrees above the horizon at [lat]/[lon] and [epochMillis]. */
    fun altitudeDeg(lat: Double, lon: Double, epochMillis: Long): Double {
        // Days since the J2000.0 epoch (2000-01-01 12:00 UTC).
        val d = (epochMillis - J2000_MS) / 86_400_000.0

        val meanLongitude = 280.460 + 0.9856474 * d
        val meanAnomaly = Math.toRadians(357.528 + 0.9856003 * d)
        // Ecliptic longitude: mean longitude corrected for the orbit's eccentricity.
        val lambda = Math.toRadians(
            meanLongitude + 1.915 * sin(meanAnomaly) + 0.020 * sin(2 * meanAnomaly),
        )
        val obliquity = Math.toRadians(23.439 - 0.0000004 * d)

        val rightAscension = atan2(cos(obliquity) * sin(lambda), cos(lambda))
        val declination = asin(sin(obliquity) * sin(lambda))

        // Greenwich mean sidereal time → local hour angle of the sun.
        val gmstHours = 18.697374558 + 24.06570982441908 * d
        val hourAngle = Math.toRadians(
            norm360(gmstHours * 15.0 + lon - Math.toDegrees(rightAscension)),
        )

        val phi = Math.toRadians(lat)
        val altitude = asin(
            sin(phi) * sin(declination) + cos(phi) * cos(declination) * cos(hourAngle),
        )
        return Math.toDegrees(altitude)
    }

    /**
     * True while there is daylight — the threshold sits at civil twilight, so the map
     * flips a little before the sun clears the horizon, which is what the eye expects.
     */
    fun isDaylight(lat: Double, lon: Double, epochMillis: Long = System.currentTimeMillis()): Boolean =
        altitudeDeg(lat, lon, epochMillis) > CIVIL_TWILIGHT_DEG

    private fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    private const val J2000_MS = 946_728_000_000L
    private const val CIVIL_TWILIGHT_DEG = -6.0
}
