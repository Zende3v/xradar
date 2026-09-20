package com.eona.app.core.model

/** The nearby list in two parts: the places to go to now, then those closed right now. */
data class NearbyResults(val open: List<Place>, val closed: List<Place>) {
    val isEmpty: Boolean get() = open.isEmpty() && closed.isEmpty()
}

/**
 * Which places the nearby search lists, and in what order, out of the pool the backend sends
 * (its 60 nearest). Pure Kotlin.
 *
 * Open places (and those whose hours are unknown) come first, nearest first — for fuel, the
 * [FuelStationPicker] still lets a station that shows a price go ahead in a city. Places
 * closed right now follow, marked as such: only those nearer than the last open one listed
 * (a closed tobacconist 20 km past twenty open ones helps nobody), at most [CLOSED_LIMIT].
 */
object NearbyPicker {

    const val LIMIT = FuelStationPicker.LIMIT
    const val CLOSED_LIMIT = 8

    fun pick(pool: List<Place>, category: PlaceCategory, fuel: FuelType?, nowMillis: Long): NearbyResults {
        val nearest = pool.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
        val (closed, open) = nearest.partition { place ->
            place.nearby?.hours?.let { NearbyLabels.state(it, nowMillis) } == OpenState.Closed
        }
        val shown = if (category == PlaceCategory.Fuel) {
            FuelStationPicker.pick(open, fuel, nowMillis)
        } else {
            open.take(LIMIT)
        }
        val reach = if (shown.size >= LIMIT) shown.maxOf { it.distanceMeters ?: 0 } else Int.MAX_VALUE
        return NearbyResults(
            open = shown,
            closed = closed.filter { (it.distanceMeters ?: Int.MAX_VALUE) <= reach }.take(CLOSED_LIMIT),
        )
    }
}
