package com.eona.app.core.model

/**
 * One turn-by-turn step from OSRM. [location] is where the maneuver happens (the
 * turn point); [name] is the road you follow after it. Distances are in metres.
 */
data class RouteStep(
    val location: GeoPoint,
    val type: String,
    val modifier: String?,
    val name: String,
    val distanceMeters: Int,
    val exit: Int?,
    /** The motorway exit's number ("8", "12a"), when the signs give one. */
    val exitNumber: String? = null,
    /** The roads the branch leads to, as the signs say them ("N 104", "A 4"). */
    val towardRefs: List<String> = emptyList(),
    /** The places the branch leads to ("Sénart", "Corbeil-Essonnes"). */
    val toward: List<String> = emptyList(),
)
