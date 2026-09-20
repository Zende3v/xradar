package com.eona.app.core.model

/** A static road sign / feature from OpenStreetMap. Pure model. */
enum class SignType(val wire: String) {
    TrafficSignals("traffic_signals"),
    Stop("stop"),
    GiveWay("give_way"),
    Crossing("crossing"),
    Roundabout("roundabout"),
    Construction("construction"),
    NoEntry("no_entry"),
    /** SNCF level crossing (croix de Saint-André). */
    LevelCrossing("level_crossing"),
    SpeedLimit("speed");

    companion object {
        fun fromWire(v: String?): SignType? = entries.firstOrNull { it.wire == v }
    }
}

data class RoadSign(val type: SignType, val lat: Double, val lon: Double, val speed: Int? = null)
