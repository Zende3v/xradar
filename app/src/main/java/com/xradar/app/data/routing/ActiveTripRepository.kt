package com.xradar.app.data.routing

import com.xradar.app.core.model.Place
import com.xradar.app.core.model.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped active navigation: the chosen destination and its computed route.
 * Search sets the destination; [com.xradar.app.feature.drive.DriveViewModel]
 * computes the route and drives the HUD from it. (Moves behind DI later.)
 */
object ActiveTripRepository {
    private val _destination = MutableStateFlow<Place?>(null)
    val destination: StateFlow<Place?> = _destination.asStateFlow()

    private val _route = MutableStateFlow<Route?>(null)
    val route: StateFlow<Route?> = _route.asStateFlow()

    fun setDestination(place: Place?) {
        _destination.value = place
        if (place == null) _route.value = null
    }

    fun setRoute(route: Route?) {
        _route.value = route
    }

    fun clear() {
        _destination.value = null
        _route.value = null
    }
}
