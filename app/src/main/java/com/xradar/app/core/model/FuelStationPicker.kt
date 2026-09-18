package com.xradar.app.core.model

/** A price for [fuel] is on show at this station: on sale, and updated within 48 h. */
fun Place.showsFuelPrice(fuel: FuelType, nowMillis: Long): Boolean = shownFuelPrice(fuel, nowMillis) != null

/** The price on show for [fuel] (see [showsFuelPrice]), in euros per litre; null when none. */
fun Place.shownFuelPrice(fuel: FuelType, nowMillis: Long): Double? =
    this.fuel?.prices?.firstOrNull { it.type == fuel && !it.outOfStock && it.isFresh(nowMillis) }?.euros

/**
 * Which stations the "Carburant" search lists, out of the pool the backend sends (its 60
 * nearest stations; [NearbyPicker] passes only those not closed right now). Pure Kotlin.
 *
 * In a city — the [LIMIT] nearest stations all within [DENSE_REACH_M] — the nearest
 * stations that show a price for the chosen fuel come first, as long as they stay within
 * [PRICED_STRETCH] times that reach; the list is then topped up with the nearest others.
 * Anywhere sparser (a small town, the countryside) it is simply the [LIMIT] nearest, as
 * before: no station is traded for a price there. With a fuel chosen, the list goes cheapest
 * first (then the stations without a price, nearest first); without one ("Proche uniquement"),
 * nearest first. Same rules as iOS.
 *
 * Measured on the official feed (Gazole): central Paris goes from 6 to 20 stations with a
 * price out of 20, Paris 15e from 9 to 19, Rennes from 16 to 19; Fougères (20th station at
 * 19 km) and the countryside (17 km) keep their nearest 20.
 */
object FuelStationPicker {

    /** Stations listed, as before. */
    const val LIMIT = 20

    /** The 20th nearest station within this distance: a dense area, prices may pick. */
    const val DENSE_REACH_M = 6_000

    /** A priced station may be picked up to this many times the 20th station's distance. */
    const val PRICED_STRETCH = 2

    fun pick(pool: List<Place>, fuel: FuelType?, nowMillis: Long): List<Place> {
        val nearest = pool.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
        if (fuel == null) return nearest.take(LIMIT)
        if (nearest.size <= LIMIT) return cheapestFirst(nearest, fuel, nowMillis)
        val reach = nearest[LIMIT - 1].distanceMeters ?: return cheapestFirst(nearest.take(LIMIT), fuel, nowMillis)
        if (reach > DENSE_REACH_M) return cheapestFirst(nearest.take(LIMIT), fuel, nowMillis)

        val cap = reach.toLong() * PRICED_STRETCH
        val priced = nearest
            .filter { (it.distanceMeters ?: Int.MAX_VALUE) <= cap && it.showsFuelPrice(fuel, nowMillis) }
            .take(LIMIT)
        val pickedIds = priced.mapTo(HashSet()) { it.id }
        val others = nearest.filter { it.id !in pickedIds }.take(LIMIT - priced.size)
        return cheapestFirst(priced + others, fuel, nowMillis)
    }

    /** The stations showing a price for [fuel], cheapest first (the nearer on a tie), then those
     *  without one, nearest first. */
    private fun cheapestFirst(stations: List<Place>, fuel: FuelType, nowMillis: Long): List<Place> {
        val nearest = stations.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
        val (priced, unpriced) = nearest.partition { it.showsFuelPrice(fuel, nowMillis) }
        // sortedBy is stable: on the same price, the nearer stays first.
        return priced.sortedBy { it.shownFuelPrice(fuel, nowMillis) } + unpriced
    }
}
