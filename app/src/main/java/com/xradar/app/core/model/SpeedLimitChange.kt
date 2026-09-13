package com.xradar.app.core.model

/** The limits a driver can propose: the same values the map draws as signs. Pure model. */
object SpeedLimits {
    val VALUES: List<Int> = listOf(20, 30, 50, 70, 80, 90, 100, 110, 130)
}

/** Where the limit shown on the HUD comes from, sent along with a proposal. */
enum class SpeedLimitSource(val wire: String) {
    /** The road's own limit: OSM, or a change drivers validated. */
    Road("map"),

    /** The VMA of the speed radar ahead, where the road has no limit mapped. */
    Radar("radar"),
}

/**
 * A proposed change of the limit at one spot, as the backend tracks it: [oldKmh] (null when
 * nothing was known there) becomes [newKmh] once enough drivers agree. Pure model.
 */
data class SpeedLimitChange(
    val id: String,
    val status: Status,
    val oldKmh: Int?,
    /** The limit now applied there, once [status] is [Status.Validated]. */
    val newKmh: Int?,
    /** People whose proposal still counts there, and how many a change needs at least. */
    val reporters: Int,
    val required: Int,
) {
    enum class Status(val wire: String) {
        Pending("pending"),
        Validated("validated"),

        /** Expired, outdated, rejected, superseded or removed. */
        Closed("closed");

        companion object {
            fun fromWire(value: String?): Status = entries.firstOrNull { it.wire == value } ?: Closed
        }
    }
}
