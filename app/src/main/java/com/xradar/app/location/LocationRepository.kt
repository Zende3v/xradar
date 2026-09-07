package com.xradar.app.location

import com.xradar.app.core.model.GpsSignal
import com.xradar.app.core.model.LocationSample
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

    fun update(sample: LocationSample) {
        _location.value = sample
        _signal.value = when {
            sample.accuracyM == null || sample.accuracyM <= GOOD_ACCURACY_M -> GpsSignal.Good
            else -> GpsSignal.Weak
        }
    }

    fun setLost() {
        _signal.value = GpsSignal.Lost
    }

    fun reset() {
        _location.value = null
        _signal.value = GpsSignal.Searching
    }
}
