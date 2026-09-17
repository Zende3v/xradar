package com.xradar.app.core.drive

import kotlin.math.max

/**
 * The ground speed shown to the driver, from the raw readings: the noise of a car standing still
 * reads 0, a reading worse than its own margin is ignored, a spike is clamped to what a car can
 * really do, and what remains is smoothed just enough not to jump. Same rules as the iOS app.
 */
class SpeedFilter {
    private var current = 0.0
    private var stopped = true
    private var startReadings = 0
    private var lastTimeMs: Long? = null

    /**
     * [speed] and [accuracy] in m/s (null: unknown), read at [timeMs]; returns the speed to show,
     * in m/s. A reading without an accuracy is trusted.
     */
    fun update(speed: Double?, accuracy: Double?, timeMs: Long): Double {
        val seconds = lastTimeMs?.let { ((timeMs - it) / 1000.0).coerceIn(0.1, 5.0) } ?: 1.0
        lastTimeMs = timeMs
        val reading = if (speed != null && speed >= 0 && (accuracy == null || accuracy <= max(2.0, speed * 0.5))) {
            speed
        } else {
            current
        }

        if (stopped) {
            startReadings = if (reading >= START_MPS) startReadings + 1 else 0
            if (startReadings < 2) return 0.0
            stopped = false
        } else if (reading < STOP_MPS && current < STOP_BELOW_MPS) {
            stopped = true
            startReadings = 0
            current = 0.0
            return 0.0
        }

        val smoothed = (reading - current) * SMOOTHING
        current += smoothed.coerceIn(-MAX_BRAKING_MPS2 * seconds, MAX_ACCELERATION_MPS2 * seconds)
        return max(current, 0.0)
    }

    fun reset() {
        current = 0.0
        stopped = true
        startReadings = 0
        lastTimeMs = null
    }

    private companion object {
        /** Under this (3,6 km/h) a reading is a stop, once the car is already slow. */
        const val STOP_MPS = 1.0
        /** Leaving a stop takes two readings in a row above this (~7 km/h). */
        const val START_MPS = 1.9
        /** A stop is only believed below this speed: at 80 km/h a sudden 0 is a glitch. */
        const val STOP_BELOW_MPS = 5.0
        /** Hard acceleration and emergency braking of a car, in m/s². */
        const val MAX_ACCELERATION_MPS2 = 4.0
        const val MAX_BRAKING_MPS2 = 9.0
        /** Weight of each new reading. */
        const val SMOOTHING = 0.6
    }
}
