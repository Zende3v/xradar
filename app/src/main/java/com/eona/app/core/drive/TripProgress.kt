package com.eona.app.core.drive

import com.eona.app.core.model.Route
import com.eona.app.core.model.TripInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Time left, distance left and arrival time, as the HUD dock shows them ("1 h 02", "12 km",
 * "20:02"). Pure Kotlin, the same rules as the iOS app.
 */
object TripProgress {

    /**
     * [remainingShare] is the part of [route] still ahead of the driver (1 before they are on it,
     * 0 at the destination): the route's own time and distance shrink with it, the same way the
     * shared trip and the group count what is left.
     */
    fun info(
        route: Route,
        remainingShare: Double = 1.0,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): TripInfo {
        val share = remainingShare.coerceIn(0.0, 1.0)
        val seconds = route.durationSeconds * share
        // Still on the way, never "0 min": the last minute shows as one.
        val minutes = if (share > 0) (seconds / 60.0).roundToInt().coerceAtLeast(1) else 0
        val remaining = if (minutes >= 60) "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}" else "$minutes min"
        val arrival = Instant.ofEpochMilli(nowMillis + (seconds * 1000).toLong()).atZone(zone).format(HHMM)
        return TripInfo(
            remainingLabel = remaining,
            distanceLabel = distanceLabel(route.distanceMeters * share),
            arrivalLabel = arrival,
        )
    }

    /** "450 m" under a kilometre (to 10 m), "4,3 km" under ten, "12 km" beyond. */
    fun distanceLabel(meters: Double): String {
        val tens = (meters / 10).roundToInt() * 10
        if (tens < 1000) return "$tens m"
        val km = meters / 1000.0
        return if (km >= 10) "${km.roundToInt()} km" else String.format(Locale.FRANCE, "%.1f km", km)
    }

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
