package com.eona.app.data.routing

import com.eona.app.core.model.Place
import com.eona.app.core.model.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped active navigation: the chosen destination and its computed route.
 * Search sets the destination; [com.eona.app.feature.drive.DriveViewModel]
 * computes the route and drives the HUD from it. (Moves behind DI later.)
 */
object ActiveTripRepository {
    private val _destination = MutableStateFlow<Place?>(null)
    val destination: StateFlow<Place?> = _destination.asStateFlow()

    /**
     * Simulated departure. Null = the driver's own position, which is the normal case;
     * set it to plan (or try out) a trip from somewhere else.
     */
    private val _start = MutableStateFlow<Place?>(null)
    val start: StateFlow<Place?> = _start.asStateFlow()

    private val _route = MutableStateFlow<Route?>(null)
    val route: StateFlow<Route?> = _route.asStateFlow()

    fun setDestination(place: Place?) {
        _destination.value = place
        if (place == null) _route.value = null
    }

    fun setStart(place: Place?) {
        _start.value = place
    }

    fun setRoute(route: Route?) {
        _route.value = route
    }

    fun clear() {
        _destination.value = null
        _route.value = null
        _start.value = null
    }
}
