package com.eona.app.core.model

/**
 * A category of nearby place the driver can jump to from the search screen. [wire] is
 * what the backend maps to an OpenStreetMap tag; the search has no fixed perimeter and
 * simply returns the nearest ones. Pure model — no Android types.
 */
enum class PlaceCategory(val wire: String, val label: String) {
    Fuel("fuel", "Carburant"),
    Charging("charging", "Bornes"),
    Parking("parking", "Parking"),
    Tobacco("tobacco", "Tabac"),
    Garage("garage", "Garage"),
    Hotel("hotel", "Hôtel"),
    Atm("atm", "Retrait"),
}
