package com.xradar.app.core.model

/** A price for [fuel] is on show at this station: on sale, and updated within 48 h. */
fun Place.showsFuelPrice(fuel: FuelType, nowMillis: Long): Boolean =
    this.fuel?.prices?.any { it.type == fuel && !it.outOfStock && it.isFresh(nowMillis) } == true

/**
 * Which stations the "Carburant" search lists, out of the pool the backend sends (the
 * nearest stations of one Overpass answer, up to 60). Pure Kotlin.
 *
 * In a city — the [LIMIT] nearest stations all within [DENSE_REACH_M] — the nearest
 * stations that show a price for the chosen fuel come first, as long as they stay within
 * [PRICED_STRETCH] times that reach; the list is then topped up with the nearest others.
 * Anywhere sparser (a small town, the countryside) it is simply the [LIMIT] nearest, as
 * before: no station is traded for a price there.
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
        if (fuel == null || nearest.size <= LIMIT) return nearest.take(LIMIT)
        val reach = nearest[LIMIT - 1].distanceMeters ?: return nearest.take(LIMIT)
        if (reach > DENSE_REACH_M) return nearest.take(LIMIT)

        val cap = reach.toLong() * PRICED_STRETCH
        val priced = nearest
            .filter { (it.distanceMeters ?: Int.MAX_VALUE) <= cap && it.showsFuelPrice(fuel, nowMillis) }
            .take(LIMIT)
        val pickedIds = priced.mapTo(HashSet()) { it.id }
        val others = nearest.filter { it.id !in pickedIds }.take(LIMIT - priced.size)
        return (priced + others).sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
    }
}
