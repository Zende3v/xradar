package com.eona.app.core.drive

/**
 * The proximity beeps of speed enforcement ahead, like a radar detector or Radarbot: the closer
 * the radar, the faster they come, and a laser burst right at it. Same rules as the iOS app.
 */
object AlertBeeps {
    /** At this distance or closer, one laser burst replaces the beeps. */
    const val BURST_METERS = 60
    /** Beeps only while driving: a car waiting at a light by a radar stays quiet. */
    const val MIN_SPEED_KMH = 10
    /** Farther than an alert shows, no beep. */
    private const val MAX_METERS = 700

    /** Milliseconds between two beeps at [meters] from the radar; null when none is due. */
    fun intervalMs(meters: Int): Long? = when {
        meters <= BURST_METERS -> null
        meters <= 150 -> 450
        meters <= 300 -> 800
        meters <= 450 -> 1_300
        meters <= MAX_METERS -> 2_000
        else -> null
    }
}
