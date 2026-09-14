package com.xradar.app.core.model

/** How a place surfaces in search (drives its icon in the UI). */
enum class PlaceKind { Home, Work, Favorite, Recent, Result }

/** A searchable/known destination. Pure model — reused by search & routing. */
data class Place(
    val id: String,
    val name: String,
    val subtitle: String,
    val kind: PlaceKind,
    val lat: Double,
    val lon: Double,
    /** Official fuel prices, only for a fuel station found by the nearby search. */
    val fuel: StationFuel? = null,
    /** Distance from the search point, for places found by the nearby search; else null. */
    val distanceMeters: Int? = null,
    /** Hours, charger, car park… for places found by the nearby search; else null. */
    val nearby: NearbyInfo? = null,
)
