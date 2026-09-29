package com.eona.app.core.drive

import com.eona.app.core.model.LocationSample
import java.util.UUID

/**
 * The driver's speeds for EONA's own traffic ("Aide au trafic partagé", 29/09): one sample every
 * [SAMPLE_EVERY_MS] of a trip under way — where, which way, how fast, under which limit — handed
 * out by batches of [BATCH] at most every [SEND_EVERY_MS], with [tripKey], a random key of the
 * trip, never the account. The more drivers cover a road, the less HERE is asked. Same rules as the
 * iOS app.
 */
class SpeedSampler(val tripKey: String = UUID.randomUUID().toString()) {
    data class Sample(
        val lat: Double,
        val lon: Double,
        val course: Double,
        val speedKmh: Double,
        val limitKmh: Int?,
        val timeMs: Long,
    )

    private val pending = ArrayDeque<Sample>()
    private var lastSampleAt = 0L
    private var lastSentAt = 0L

    /** A fix of the trip at [now], under [limitKmh] (the one shown); without a course, nothing. */
    fun add(fix: LocationSample, limitKmh: Int?, now: Long = System.currentTimeMillis()) {
        if (now - lastSampleAt < SAMPLE_EVERY_MS) return
        val course = fix.bearingDeg?.toDouble() ?: return
        lastSampleAt = now
        pending.addLast(Sample(fix.latitude, fix.longitude, course, fix.speedKmh.toDouble().coerceAtLeast(0.0), limitKmh, now))
        while (pending.size > MAX_PENDING) pending.removeFirst()
    }

    /** The samples to send at [now], taken out; null when it is not time yet or there are none. */
    fun due(now: Long = System.currentTimeMillis()): List<Sample>? {
        if (pending.isEmpty() || now - lastSentAt < SEND_EVERY_MS) return null
        lastSentAt = now
        val batch = mutableListOf<Sample>()
        while (batch.size < BATCH && pending.isNotEmpty()) batch += pending.removeFirst()
        return batch
    }

    private companion object {
        const val SAMPLE_EVERY_MS = 15_000L
        const val SEND_EVERY_MS = 60_000L
        const val BATCH = 20
        const val MAX_PENDING = 40
    }
}
