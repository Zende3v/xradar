package com.xradar.app.core.model

/**
 * One position reading. Pure model (no Android [android.location.Location]) so the
 * domain/alert engine and a future iOS port can consume it unchanged.
 */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    /** Ground speed in metres/second, or null if the fix has none. */
    val speedMps: Float?,
    /** Heading in degrees (0..360, clockwise from north), or null. */
    val bearingDeg: Float?,
    /** Horizontal accuracy in metres, or null. */
    val accuracyM: Float?,
    /** Elapsed-realtime timestamp in millis. */
    val timeMs: Long,
) {
    val speedKmh: Float get() = (speedMps ?: 0f) * 3.6f
}
