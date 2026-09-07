package com.xradar.app.core.geo

import com.xradar.app.core.model.GeoPoint
import kotlin.math.cos

/**
 * A polyline with cumulative distances, for map-matching the driver to the route:
 * snap the GPS fix onto the line, know how far along it we are, read the pose at a
 * given distance, and trim the part already driven ("the arrow eats the line").
 * Pure Kotlin (equirectangular metres) — reusable on iOS/KMP later.
 */
class RoutePath(val points: List<GeoPoint>) {

    private val cum = DoubleArray(points.size)
    val totalMeters: Double

    init {
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + Geo.haversine(
                points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon,
            )
        }
        totalMeters = if (points.isEmpty()) 0.0 else cum[points.size - 1]
    }

    /** Snapped point, distance along the route, off-route distance, and route bearing. */
    data class Match(
        val lat: Double,
        val lon: Double,
        val alongMeters: Double,
        val offRouteMeters: Double,
        val bearingDeg: Double,
    )

    /** Nearest point on the route to (lat, lon). Full scan — call at GPS rate, not per frame. */
    fun match(lat: Double, lon: Double): Match? {
        if (points.size < 2) return null
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(lat))
        val px = lon * mLon
        val py = lat * mLat
        var best = Double.MAX_VALUE
        var bi = 0
        var bt = 0.0
        var bsx = 0.0
        var bsy = 0.0
        for (i in 0 until points.size - 1) {
            val ax = points[i].lon * mLon
            val ay = points[i].lat * mLat
            val bx = points[i + 1].lon * mLon
            val by = points[i + 1].lat * mLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val sx = ax + t * dx
            val sy = ay + t * dy
            val d2 = (px - sx) * (px - sx) + (py - sy) * (py - sy)
            if (d2 < best) {
                best = d2; bi = i; bt = t; bsx = sx; bsy = sy
            }
        }
        val snapLat = bsy / mLat
        val snapLon = bsx / mLon
        val along = cum[bi] + bt * (cum[bi + 1] - cum[bi])
        val off = Geo.haversine(lat, lon, snapLat, snapLon)
        val bearing = Geo.bearing(points[bi].lat, points[bi].lon, points[bi + 1].lat, points[bi + 1].lon)
        return Match(snapLat, snapLon, along, off, bearing)
    }

    /** Interpolated point + route bearing at a cumulative distance. */
    fun poseAt(distanceMeters: Double): Pair<GeoPoint, Double> {
        if (points.size < 2) return (points.firstOrNull() ?: GeoPoint(0.0, 0.0)) to 0.0
        val d = distanceMeters.coerceIn(0.0, totalMeters)
        val i = segmentIndexFor(d)
        val segLen = cum[i + 1] - cum[i]
        val t = if (segLen <= 0.0) 0.0 else ((d - cum[i]) / segLen).coerceIn(0.0, 1.0)
        val a = points[i]
        val b = points[i + 1]
        val point = GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        return point to Geo.bearing(a.lat, a.lon, b.lat, b.lon)
    }

    /** Remaining route from a distance to the end (the part still ahead of the driver). */
    fun trimFrom(distanceMeters: Double): List<GeoPoint> {
        if (points.size < 2) return points
        val d = distanceMeters.coerceIn(0.0, totalMeters)
        val i = segmentIndexFor(d)
        val (head, _) = poseAt(d)
        val rest = ArrayList<GeoPoint>(points.size - i)
        rest.add(head)
        for (j in i + 1 until points.size) rest.add(points[j])
        return rest
    }

    private fun segmentIndexFor(d: Double): Int {
        var lo = 0
        var hi = points.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cum[mid] <= d) lo = mid else hi = mid - 1
        }
        return lo
    }
}
