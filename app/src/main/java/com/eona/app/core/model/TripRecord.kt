package com.eona.app.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A completed trip in the history. Stores raw values; labels are derived for display. Pure
 * model (JVM time only), same fields and labels as the iOS app.
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
    /** The route's estimate for the trip, in seconds; null when no route was known (or for a
     *  trip recorded before estimates were kept). */
    val plannedSeconds: Int? = null,
    /** Standstills of 10 s or more on the way, and their total time. */
    val stops: Int = 0,
    val stoppedSeconds: Int = 0,
    /** The alerts met on the way, per kind. */
    val events: Map<AlertType, Int> = emptyMap(),
) {
    val distanceLabel: String
        get() {
            val km = distanceMeters / 1000.0
            return if (km >= 10) "${Math.round(km)} km" else "%.1f km".format(Locale.FRANCE, km)
        }

    /** "45 min", "1 h 05". */
    val durationLabel: String get() = duration(durationSeconds)

    /** The route's estimate, "41 min"; null without one. */
    val plannedLabel: String? get() = plannedSeconds?.let { duration(it) }

    /** The real time against the estimate: "+4 min", "−3 min", or "À l'heure" within a minute; null without an estimate. */
    val delayLabel: String?
        get() {
            val planned = plannedSeconds ?: return null
            val delta = durationSeconds - planned
            if (abs(delta) < 60) return "À l'heure"
            return (if (delta > 0) "+" else "−") + duration(abs(delta))
        }

    /** Over the whole trip, stops included, in km/h. */
    val averageSpeedKmh: Int
        get() = if (durationSeconds <= 0) 0 else (distanceMeters.toDouble() / durationSeconds * 3.6).roundToInt()

    /** "Aucun", "1 arrêt · 45 s", "3 arrêts · 2 min". */
    val stopsLabel: String
        get() = if (stops <= 0) "Aucun" else "$stops arrêt${if (stops > 1) "s" else ""} · ${duration(stoppedSeconds, withSeconds = true)}"

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

    companion object {
        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE)

        /** "45 min", "1 h 05"; under a minute, "40 s" when [withSeconds]. */
        fun duration(total: Int, withSeconds: Boolean = false): String {
            if (withSeconds && total < 60) return "$total s"
            val minutes = total / 60
            return if (minutes >= 60) "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}" else "$minutes min"
        }
    }
}

/** The name a trip's events are stored under on the backend (as the iOS app names them). */
val AlertType.wireName: String
    get() = name.replaceFirstChar { it.lowercase() }

fun alertTypeFromWire(name: String): AlertType? = AlertType.entries.firstOrNull { it.wireName == name }
