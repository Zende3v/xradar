package com.xradar.app.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A completed trip in the local history. Stores raw values; labels are derived
 * for display. Pure model (JVM time only) — reusable by iOS/KMP later.
 */
data class TripRecord(
    val id: String,
    val startedAt: Long,
    val fromLabel: String,
    val toLabel: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val alertsCount: Int,
    val topSpeedKmh: Int,
) {
    val distanceLabel: String
        get() {
            val km = distanceMeters / 1000.0
            return if (km >= 10) "${Math.round(km)} km" else "%.1f km".format(Locale.FRANCE, km)
        }

    val durationLabel: String
        get() {
            val minutes = durationSeconds / 60
            return if (minutes >= 60) "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
            else "$minutes min"
        }

    val dateLabel: String
        get() {
            val zone = ZoneId.systemDefault()
            val dateTime = Instant.ofEpochMilli(startedAt).atZone(zone)
            val today = LocalDate.now(zone)
            val day = dateTime.toLocalDate()
            val time = dateTime.format(HHMM)
            return when (day) {
                today -> "Aujourd'hui · $time"
                today.minusDays(1) -> "Hier · $time"
                else -> "${dateTime.format(DATE)} · $time"
            }
        }

    private companion object {
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE)
    }
}
