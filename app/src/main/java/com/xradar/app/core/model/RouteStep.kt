package com.xradar.app.core.model

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
)
