package com.xradar.app.core.model

/** Quality of the current GPS fix, surfaced to the driver. */
enum class GpsSignal {
    /** No fix yet (acquiring). */
    Searching,

    /** Good, recent fix. */
    Good,

    /** Fix available but low accuracy. */
    Weak,

    /** Location turned off or no update for a while. */
    Lost,
}
