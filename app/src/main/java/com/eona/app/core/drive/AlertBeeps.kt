package com.eona.app.core.drive

/**
 * The proximity beeps of speed enforcement ahead, like a radar detector or Radarbot: the closer
 * the radar, the faster they come, and a laser burst right at it. Nothing before [START_METERS].
 * Same rules as the iOS app (AlertBeeps.swift, build 22).
 */
object AlertBeeps {
    /** The first beep sounds here, exactly: no radar sound farther out (Arthur, 01/10). */
    const val START_METERS = 200
    /** At this distance or closer, one laser burst replaces the beeps. */
    const val BURST_METERS = 60
    /** Beeps only while driving: a car waiting at a light by a radar stays quiet. */
    const val MIN_SPEED_KMH = 10

    /** Seconds between two beeps at [meters] from the radar; null when none is due (farther than
     *  the first beep, or already in the burst). */
    fun interval(meters: Int): Double? = when {
        meters <= BURST_METERS -> null
        meters <= 150 -> 0.45
        meters <= START_METERS -> 0.8
        else -> null
    }
}

/**
 * Beeps calés sur distance réelle, pas sur dernier fix GPS. Fix vieux de 1 s à 130 km/h : 36 m
 * d'écart. Distance extrapolée depuis heure du fix, vitesse, latence de sortie audio. Copie
 * d'EnforcementBeeps (iOS, build 22).
 */
class EnforcementBeeps {
    enum class Cue { Beep, Burst }

    /** What to play now, whether the audio must be ready, and when to look again (seconds). */
    data class Step(val cue: Cue?, val armed: Boolean, val wakeIn: Double)

    private val started = HashSet<String>()
    private val bursts = HashSet<String>()
    private var lastBeepAt = Double.NEGATIVE_INFINITY

    /**
     * [key] nearest enforcement alert (null: none), [metersAtFix] its distance at the fix, [now]
     * seconds on any steady clock, [latency] audio output delay. [speaking]: the voice talks; only
     * in-between beeps wait for it, never the first one nor the burst.
     */
    fun step(
        key: String?,
        metersAtFix: Double,
        speedMps: Double,
        fixAgeSeconds: Double,
        now: Double,
        latency: Double,
        speaking: Boolean,
    ): Step {
        if (started.size > 300) {
            started.clear()
            bursts.clear()
        }
        val speed = speedMps.coerceAtLeast(0.0)
        if (key == null || speed * 3.6 < AlertBeeps.MIN_SPEED_KMH) return Step(null, false, TICK_SECONDS)
        // Distance où sera la voiture quand le son atteint l'oreille.
        val lead = speed * latency.coerceIn(0.0, MAX_LATENCY_SECONDS)
        val heard = predictedMeters(metersAtFix, speed, fixAgeSeconds) - lead
        val start = AlertBeeps.START_METERS.toDouble()
        val burst = AlertBeeps.BURST_METERS.toDouble()
        val armed = heard <= start + speed * ARM_SECONDS
        fun until(meters: Double) = (heard - meters) / speed

        if (heard <= burst) {
            val cue = if (bursts.add(key)) Cue.Burst else null
            started.add(key)
            return Step(cue, armed, TICK_SECONDS)
        }
        if (heard > start) return Step(null, armed, wake(until(start)))
        val meters = kotlin.math.ceil(heard).toInt()
        var cue: Cue? = null
        if (started.add(key)) {
            cue = Cue.Beep
            lastBeepAt = now
        } else if (!speaking) {
            val interval = AlertBeeps.interval(meters)
            if (interval != null && now - lastBeepAt >= interval) {
                cue = Cue.Beep
                lastBeepAt = now
            }
        }
        // Voix en cours : bip suivant attend, sans boucle serrée.
        val nextBeep = if (speaking) TICK_SECONDS else (AlertBeeps.interval(meters) ?: TICK_SECONDS) - (now - lastBeepAt)
        return Step(cue, armed, wake(minOf(nextBeep, until(burst))))
    }

    companion object {
        /** Jamais d'extrapolation au-delà : GPS perdu, tunnel. */
        const val MAX_FIX_AGE_SECONDS = 2.5
        /** Audio préparé ce délai avant premier bip. */
        const val ARM_SECONDS = 1.5
        /** Tour de boucle loin des seuils. */
        const val TICK_SECONDS = 0.1
        /** Latence de sortie retenue au plus (Bluetooth compris). */
        const val MAX_LATENCY_SECONDS = 0.5

        /** Distance au radar maintenant : distance au fix moins chemin parcouru depuis. */
        fun predictedMeters(atFix: Double, speedMps: Double, fixAgeSeconds: Double): Double =
            atFix - speedMps.coerceAtLeast(0.0) * fixAgeSeconds.coerceIn(0.0, MAX_FIX_AGE_SECONDS)

        /** Sommeil borné : jamais plus d'un tour, jamais zéro. */
        private fun wake(seconds: Double) = seconds.coerceIn(0.005, TICK_SECONDS)
    }
}
