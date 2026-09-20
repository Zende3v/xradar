package com.eona.app.location

import com.eona.app.core.drive.SpeedFilter
import com.eona.app.core.model.GpsSignal
import com.eona.app.core.model.LocationSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped holder for the latest position + signal quality. The foreground
 * [LocationService] writes here; the UI/ViewModel reads. Kept as a simple
 * singleton for now — it moves behind DI when we introduce it.
 */
object LocationRepository {

    private const val GOOD_ACCURACY_M = 30f

    private val _location = MutableStateFlow<LocationSample?>(null)
    val location: StateFlow<LocationSample?> = _location.asStateFlow()

    private val _signal = MutableStateFlow(GpsSignal.Searching)
    val signal: StateFlow<GpsSignal> = _signal.asStateFlow()

    /** The raw speed spikes at a stop and jumps while driving: it is published filtered. */
    private val speedFilter = SpeedFilter()

    @Synchronized
    fun update(sample: LocationSample) {
        val speed = speedFilter.update(sample.speedMps?.toDouble(), sample.speedAccuracyMps?.toDouble(), sample.timeMs)
        _location.value = sample.copy(speedMps = speed.toFloat())
        _signal.value = when {
            sample.accuracyM == null || sample.accuracyM <= GOOD_ACCURACY_M -> GpsSignal.Good
            else -> GpsSignal.Weak
        }
    }

    fun setLost() {
        _signal.value = GpsSignal.Lost
    }

    @Synchronized
    fun reset() {
        speedFilter.reset()
        _location.value = null
        _signal.value = GpsSignal.Searching
    }
}
