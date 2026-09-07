package com.xradar.app.core.model

/** Another driver sharing their position live. Pure model. */
data class LiveUser(
    val id: String,
    val username: String?,
    val lat: Double,
    val lon: Double,
    val bearingDeg: Float?,
    val avatarUrl: String?,
)
