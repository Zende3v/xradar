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

/**
 * One slowed stretch, in metres along the route polyline the backend received, the time lost on
 * it, and who says so: "tomtom", "crowd" for the drivers' own jams, "datagouv" for the DIR's
 * feeds ([kind]: speed, works, incident, queue, closed).
 */
data class TrafficStretch(
    val fromMeters: Double,
    val toMeters: Double,
    val level: TrafficLevel,
    val delaySeconds: Int? = null,
    val source: String = TOMTOM,
    val kind: String? = null,
) {
    companion object {
        /** A section the backend sends without a source is TomTom's (the live source before HERE). */
        const val TOMTOM = "tomtom"
        /** HERE's live traffic, since 30/09. */
        const val HERE = "here"
        const val CROWD = "crowd"
        const val DATAGOUV = "datagouv"
    }
}

/**
 * The traffic on the route being followed: its slowed stretches (TomTom's, and the drivers' own
 * jams where they cost more), measured along a polyline [totalMeters] long (the backend's measure
 * of the route the app sent). Empty: a clear road.
 * [worthChecking] déclenche le contrôle automatique d’un détour possible.
 */
data class RouteTraffic(
    val totalMeters: Double,
    val stretches: List<TrafficStretch>,
    val worthChecking: Boolean = false,
    /** TomTom's time with today's traffic for the route from [startMeters] on; null without TomTom. */
    val travelSeconds: Int? = null,
    /** Where TomTom's time starts, in the same metres: the driver's place when it was asked. */
    val startMeters: Double = 0.0,
) {
    /** Whether a slowed stretch covers [meters] along the route ([routeMeters], the app's own
     *  length of it: the backend's measure is scaled to it). */
    fun slowed(meters: Double, routeMeters: Double): Boolean {
        val scale = if (totalMeters > 0) routeMeters / totalMeters else 1.0
        return stretches.any { it.fromMeters * scale <= meters && meters <= it.toMeters * scale }
    }
}

/**
 * What the backend said of the rest of the route (/api/traffic/route with raw: true): each source
 * whole, in metres of the polyline sent ([totalMeters] long); [tomtom] whether TomTom answered,
 * [datagouvShown] which ETA to show (D2.6), [minGapSeconds] the least wait before the next
 * TomTom recalage (D3.1).
 */
data class TrafficAnswer(
    val totalMeters: Double,
    val stretches: List<TrafficStretch>,
    val travelSeconds: Int?,
    val tomtom: Boolean,
    val datagouvShown: Boolean,
    val minGapSeconds: Int,
    val worthChecking: Boolean,
) {
    /** The stretches in metres of the whole route ([routeMeters] long), the answer being about its rest from [startMeters]. */
    fun placed(startMeters: Double, routeMeters: Double): List<TrafficStretch> {
        val scale = if (totalMeters > 0) (routeMeters - startMeters).coerceAtLeast(0.0) / totalMeters else 1.0
        return stretches.map { it.copy(fromMeters = startMeters + it.fromMeters * scale, toMeters = startMeters + it.toMeters * scale) }
    }
}

/**
 * The traffic of the route being followed, by source (D2.6, D2.7), in metres of the app's own
 * route ([routeMeters] long): the live source's last answer ([tomtom]: HERE's since 30/09,
 * [travelSeconds] from [tomtomFrom] when it gives one),
 * the drivers' jams and data.gouv's, each whole. [merged] joins them the way the backend does: the
 * drivers' jams where they cost more than TomTom, then data.gouv for its extra only (a closure
 * always), and only when asked: with and without it, the two ETAs. Same rules as the iOS app.
 */
data class TrafficParts(
    val routeMeters: Double,
    val tomtom: List<TrafficStretch> = emptyList(),
    val travelSeconds: Int? = null,
    val tomtomFrom: Double = 0.0,
    val crowd: List<TrafficStretch> = emptyList(),
    val datagouv: List<TrafficStretch> = emptyList(),
    val datagouvShown: Boolean = false,
    val worthChecking: Boolean = false,
) {
    fun merged(withDatagouv: Boolean = datagouvShown): RouteTraffic {
        val known = tomtom + extraOver(tomtom, crowd, keepFree = false)
        val all = if (withDatagouv) known + extraOver(known, datagouv, keepFree = true) else known
        return RouteTraffic(routeMeters, all.sortedBy { it.fromMeters }, worthChecking, travelSeconds, tomtomFrom)
    }

    /** The sources that said something on this route (the live one by its own name: here, tomtom). */
    val sources: Set<String>
        get() = buildSet {
            tomtom.forEach { add(it.source) }
            if (travelSeconds != null && tomtom.isEmpty()) add(TrafficStretch.HERE)
            if (crowd.isNotEmpty()) add(TrafficStretch.CROWD)
            if (datagouv.isNotEmpty()) add(TrafficStretch.DATAGOUV)
        }

    companion object {
        /**
         * [zones] beyond what [known] already counts where they overlap: only their extra delay;
         * with [keepFree], a closure or a stretch without delay shows anyway.
         */
        fun extraOver(known: List<TrafficStretch>, zones: List<TrafficStretch>, keepFree: Boolean): List<TrafficStretch> =
            zones.mapNotNull { zone ->
                val counted = known.sumOf { s ->
                    val overlap = minOf(s.toMeters, zone.toMeters) - maxOf(s.fromMeters, zone.fromMeters)
                    if (overlap > 0 && s.toMeters > s.fromMeters) (s.delaySeconds ?: 0) * overlap / (s.toMeters - s.fromMeters) else 0.0
                }
                val extra = Math.round((zone.delaySeconds ?: 0) - counted).toInt()
                when {
                    extra > 0 -> zone.copy(delaySeconds = extra)
                    keepFree && (zone.level == TrafficLevel.Closed || (zone.delaySeconds ?: 0) == 0) -> zone.copy(delaySeconds = 0)
                    else -> null
                }
            }
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
