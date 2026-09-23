package com.eona.app.core.geo

import com.eona.app.core.model.GeoPoint
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * A long line made light enough to draw fast, with the least visible change: points that sit
 * within a few metres of the line through their neighbours go (Douglas–Peucker, in local metres).
 * The tolerance grows only as much as needed to stay under a point budget, so a short trip keeps
 * every curve and a 500 km one keeps its shape at every zoom the map will show. Pure Kotlin.
 */
object LineSimplifier {
    /** [points] with at most [maxPoints] of them; the first and the last are always kept. */
    fun simplify(points: List<GeoPoint>, maxPoints: Int, startToleranceMeters: Double = 8.0): List<GeoPoint> {
        if (points.size <= maxOf(maxPoints, 2)) return points
        // Local metres around the line's middle: good enough for a few hundred kilometres.
        val middle = points[points.size / 2]
        val metersPerLat = 111_320.0
        val metersPerLon = 111_320.0 * maxOf(cos(Math.toRadians(middle.lat)), 0.1)
        val xs = DoubleArray(points.size) { points[it].lon * metersPerLon }
        val ys = DoubleArray(points.size) { points[it].lat * metersPerLat }

        var tolerance = startToleranceMeters
        var kept = points
        repeat(12) {
            val keep = douglasPeucker(xs, ys, tolerance)
            kept = points.filterIndexed { i, _ -> keep[i] }
            if (kept.size <= maxPoints) return kept
            tolerance *= 1.6
        }
        return kept
    }

    private fun douglasPeucker(xs: DoubleArray, ys: DoubleArray, tolerance: Double): BooleanArray {
        val keep = BooleanArray(xs.size)
        keep[0] = true
        keep[xs.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to xs.size - 1)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            val ax = xs[first]
            val ay = ys[first]
            val dx = xs[last] - ax
            val dy = ys[last] - ay
            val length2 = dx * dx + dy * dy
            var worst = 0.0
            var index = -1
            for (i in first + 1 until last) {
                val t = if (length2 == 0.0) 0.0 else (((xs[i] - ax) * dx + (ys[i] - ay) * dy) / length2).coerceIn(0.0, 1.0)
                val ex = xs[i] - (ax + t * dx)
                val ey = ys[i] - (ay + t * dy)
                val distance = sqrt(ex * ex + ey * ey)
                if (distance > worst) {
                    worst = distance
                    index = i
                }
            }
            if (index > 0 && worst > tolerance) {
                keep[index] = true
                stack.addLast(first to index)
                stack.addLast(index to last)
            }
        }
        return keep
    }
}
