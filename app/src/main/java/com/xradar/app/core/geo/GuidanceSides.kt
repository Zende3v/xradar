package com.xradar.app.core.geo

import com.xradar.app.core.model.RouteStep
import kotlin.math.abs

/**
 * The side of a turn as the road itself bends there: when the router's words disagree with a
 * clear bend the other way, the geometry wins, so the arrow and the voice match the road. Only
 * real turns: a fork, a ramp or a slight turn is named against the other branch, which can bend
 * either way.
 */
object GuidanceSides {
    private const val CLEAR_BEND_DEG = 30.0
    private const val PROBE_M = 15.0
    private val TURN_TYPES = setOf("turn", "new name", "continue", "end of road")
    private val TURN_MODIFIERS = setOf("left", "right", "sharp left", "sharp right")

    /** [steps] with their sides checked; [stepAlong] holds each one's distance along [path]. */
    fun checked(steps: List<RouteStep>, stepAlong: DoubleArray, path: RoutePath?): List<RouteStep> {
        if (path == null || stepAlong.size != steps.size) return steps
        return steps.mapIndexed { i, step -> checked(step, stepAlong[i], path) }
    }

    fun checked(step: RouteStep, along: Double, path: RoutePath): RouteStep {
        val modifier = step.modifier ?: return step
        if (step.type !in TURN_TYPES || modifier !in TURN_MODIFIERS) return step
        if (along <= PROBE_M || along >= path.totalMeters - PROBE_M) return step
        var bend = (path.poseAt(along + PROBE_M).second - path.poseAt(along - PROBE_M).second) % 360.0
        if (bend > 180) bend -= 360 else if (bend < -180) bend += 360
        val saysRight = modifier.contains("right")
        if (abs(bend) < CLEAR_BEND_DEG || (bend > 0) == saysRight) return step
        val fixed = if (saysRight) modifier.replace("right", "left") else modifier.replace("left", "right")
        return step.copy(modifier = fixed)
    }
}
