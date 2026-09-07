package com.xradar.app.core.model

/** How a place surfaces in search (drives its icon in the UI). */
enum class PlaceKind { Home, Work, Favorite, Recent, Result }

/** A searchable/known destination. Pure model — reused by search & routing. */
data class Place(
    val id: String,
    val name: String,
    val subtitle: String,
    val kind: PlaceKind,
    val lat: Double,
    val lon: Double,
)
