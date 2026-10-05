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

    /** Destination choisie, en attente du choix d'itinéraire (Rapide, Éco). Null : aucun choix. */
    private val _proposal = MutableStateFlow<Place?>(null)
    val proposal: StateFlow<Place?> = _proposal.asStateFlow()

    /** Destination choisie : choix d'itinéraire d'abord, trajet ensuite. Null : choix refermé. */
    fun propose(place: Place?) {
        _proposal.value = place
    }

    /** Étapes restantes avant la destination, dans l'ordre ; retirées une à une en route. */
    private val _stops = MutableStateFlow<List<Place>>(emptyList())
    val stops: StateFlow<List<Place>> = _stops.asStateFlow()

    /** Étapes au plus, comme le backend. */
    const val MAX_STOPS = 10

    fun setDestination(place: Place?) {
        _destination.value = place
        if (place == null) {
            _route.value = null
            _stops.value = emptyList()
        }
    }

    /** Une étape de plus, en dernier avant la destination. Liste pleine, déjà prévue ou destination : rien. */
    fun addStop(place: Place) {
        val now = _stops.value
        if (now.size >= MAX_STOPS || now.any { it.id == place.id } || place.id == _destination.value?.id || place.id == _proposal.value?.id) return
        _stops.value = now + place
    }

    /** Étapes réordonnées ou retirées par le conducteur. */
    fun setStops(places: List<Place>) {
        _stops.value = places.take(MAX_STOPS)
    }

    /** Première étape atteinte : retirée. */
    fun stopReached() {
        if (_stops.value.isNotEmpty()) _stops.value = _stops.value.drop(1)
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
        _stops.value = emptyList()
    }
}
