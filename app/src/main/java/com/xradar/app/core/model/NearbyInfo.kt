package com.xradar.app.core.model

/** Whether a place is open right now, as the backend read its hours. */
enum class OpenState { Open, Closed, Unknown }

/** One opening slot of the day, "07:00" to "12:00" ("24:00" for midnight). */
data class TimeSlot(val from: String, val to: String)

data class OpeningHours(
    val state: OpenState,
    /** Open with no change ahead: 24/7. */
    val alwaysOpen: Boolean,
    /** Today's opening slots. */
    val today: List<TimeSlot>,
    /** When [state] changes next (epoch millis), within a week; null otherwise. */
    val nextChangeMillis: Long?,
    /** True when the hours come from the official fuel feed rather than OpenStreetMap. */
    val official: Boolean = false,
)

data class ChargingInfo(
    /** Strongest charging power, kW. */
    val maxKw: Double?,
    /** "CCS", "CHAdeMO", "Type 2"… strongest first. */
    val connectors: List<String>,
    /** Charging points. */
    val points: Int?,
)

enum class ParkingType { Underground, MultiStorey, Rooftop, Surface, StreetSide }

data class ParkingInfo(
    /** true: paying, false: free, null: unknown. */
    val fee: Boolean?,
    val type: ParkingType?,
    val capacity: Int?,
    val parkAndRide: Boolean,
)

/** What the nearby search knows about a place beyond its name. Pure model. */
data class NearbyInfo(
    val brand: String? = null,
    val hours: OpeningHours? = null,
    /** Reserved for the customers of a shop or a hotel. */
    val customersOnly: Boolean = false,
    val charging: ChargingInfo? = null,
    val parking: ParkingInfo? = null,
    val stars: Int? = null,
)
