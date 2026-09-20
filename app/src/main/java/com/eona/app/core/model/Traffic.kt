package com.eona.app.core.model

/** How slow a stretch of the route is, as the backend says (TomTom, or the drivers' jams). */
enum class TrafficLevel(val wire: String) {
    /** Slower than usual. */
    Slow("slow"),
    /** A traffic jam. */
    Jam("jam"),
    /** A heavy jam: a lot of time lost. */
    Heavy("heavy"),
    /** The road is closed. */
    Closed("closed");

    companion object {
        fun fromWire(value: String?): TrafficLevel? = entries.firstOrNull { it.wire == value }
    }
}

/** One slowed stretch, in metres along the route polyline the backend received, and the time lost on it. */
data class TrafficStretch(
    val fromMeters: Double,
    val toMeters: Double,
    val level: TrafficLevel,
    val delaySeconds: Int? = null,
)

/**
 * The traffic on the route being followed: its slowed stretches (TomTom's, and the drivers' own
 * jams where they cost more), measured along a polyline [totalMeters] long (the backend's measure
 * of the route the app sent). Empty: a clear road. [worthChecking]: the backend says a faster
 * route may exist ahead of the driver ("Éviter les bouchons" then asks for one).
 */
data class RouteTraffic(
    val totalMeters: Double,
    val stretches: List<TrafficStretch>,
    val worthChecking: Boolean = false,
) {
    /** Whether a slowed stretch covers [meters] along the route ([routeMeters], the app's own
     *  length of it: the backend's measure is scaled to it). */
    fun slowed(meters: Double, routeMeters: Double): Boolean {
        val scale = if (totalMeters > 0) routeMeters / totalMeters else 1.0
        return stretches.any { it.fromMeters * scale <= meters && meters <= it.toMeters * scale }
    }
}

/**
 * A faster way to the destination around the traffic, and the time it saves; or the way around
 * a closed road ([closed]), whatever it costs.
 */
data class FasterRoute(
    val route: Route,
    val gainSeconds: Int,
    val closed: Boolean = false,
)
