package com.xradar.app.core.model

import java.time.OffsetDateTime

/** A fuel as the official feed (prix-carburants.gouv.fr) names it. Pure model. */
enum class FuelType(val wire: String, val label: String) {
    Gazole("Gazole", "Gazole"),
    SP95("SP95", "SP95"),
    SP98("SP98", "SP98"),
    E10("E10", "E10"),
    E85("E85", "E85"),
    GPLc("GPLc", "GPLc");

    companion object {
        fun fromWire(value: String?): FuelType? = entries.firstOrNull { it.wire == value }
    }
}

/** One official price at a station. Pure model (JVM time only). */
data class FuelPrice(
    val type: FuelType,
    /** Euros per litre. */
    val euros: Double,
    /** When the station last updated it, ISO-8601 with offset; null when unknown. */
    val updatedAt: String?,
    /** The station reports this fuel as not on sale right now. */
    val outOfStock: Boolean = false,
) {
    /** [updatedAt] as epoch millis, or null when missing or unreadable. */
    val updatedAtMillis: Long?
        get() = updatedAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    /** Only a price the station updated within [FRESH_MS] is shown; an older one counts as unknown. */
    fun isFresh(nowMillis: Long): Boolean {
        val at = updatedAtMillis ?: return false
        return nowMillis - at <= FRESH_MS
    }

    companion object {
        /** 48 hours. */
        const val FRESH_MS = 48L * 60 * 60 * 1000
    }
}

/**
 * The official prices matched to a station found by the nearby search. [matchedBy] is
 * "id" (OpenStreetMap carries the official id) or "position" (unambiguous nearest).
 */
data class StationFuel(
    val stationId: String,
    val matchedBy: String,
    val prices: List<FuelPrice>,
)
