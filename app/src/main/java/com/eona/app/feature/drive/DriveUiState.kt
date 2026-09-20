package com.eona.app.feature.drive

import com.eona.app.core.model.GeoPoint
import com.eona.app.core.model.GpsSignal
import com.eona.app.core.model.GuidanceInstruction
import com.eona.app.core.model.LocationSample
import com.eona.app.core.model.Radar
import com.eona.app.core.model.RadarZone
import com.eona.app.core.model.RoadAlert
import com.eona.app.core.model.SpeedLimitSource
import com.eona.app.core.model.SpeedStatus
import com.eona.app.core.model.TripInfo
import com.eona.app.core.model.UserReport
import com.eona.app.media.MediaPlaybackState

/** Everything the driving HUD needs to render one frame. */
/**
 * A faster way around the traffic was just taken: the banner saying how much time it saves, or
 * that it goes around a closed road.
 */
data class FasterRouteNotice(
    val gainMinutes: Int,
    val closedRoad: Boolean = false,
    val id: Long = System.nanoTime(),
)

/**
 * The trip just reached its destination: what the arrival card shows, a few seconds, before the
 * HUD goes back to simply driving.
 */
data class TripArrival(
    val toLabel: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val alertsCount: Int,
    val id: Long = System.nanoTime(),
)

/** "Ralentissement du trafic ?", asked a few seconds about a slowdown nobody knows of yet. */
data class SlowdownPrompt(
    val slowdown: com.eona.app.core.drive.Slowdown,
    val id: Long = System.nanoTime(),
)

data class DriveUiState(
    val speedKmh: Int,
    val speedLimitKmh: Int?,
    /** Non-null only while navigating to a destination; null when simply driving. */
    val trip: TripInfo?,
    /** The nearest live alert (voice and trip counting); the first of [alerts]. */
    val alert: RoadAlert?,
    val gpsSignal: GpsSignal,
    /** Every live alert, nearest first — the HUD stacks them all, none is dropped. */
    val alerts: List<RoadAlert> = emptyList(),
    /** Latest raw fix, for the map to follow. */
    val location: LocationSample? = null,
    /** Radars around the driver, to plot on the map. */
    val radars: List<Radar> = emptyList(),
    /** Crowdsourced reports around the driver, to plot on the map. */
    val reports: List<UserReport> = emptyList(),
    /** Probable radar-car zones (circles) around the driver. */
    val zones: List<RadarZone> = emptyList(),
    /** OSM road signs near the driver. */
    val signs: List<com.eona.app.core.model.RoadSign> = emptyList(),
    /** Active route polyline, if navigating to a destination. */
    val routePoints: List<GeoPoint> = emptyList(),
    /** Next maneuver to display, if navigating with steps available. */
    val guidance: GuidanceInstruction? = null,
    /** A destination was picked but no route came back (network / provider down). */
    val routeError: Boolean = false,
    /** Music in Spotify / Apple Music / Deezer, read from the system media sessions. */
    val media: MediaPlaybackState = MediaPlaybackState.PermissionMissing,
    /** The music banner is open. HUD state only, never persisted. */
    val musicOpen: Boolean = false,
    /** Where [speedLimitKmh] comes from: the road's own limit or a radar's VMA. */
    val speedLimitSource: SpeedLimitSource? = null,
    /** Traffic on the route being followed (TomTom's and the drivers' jams); null until known. */
    val traffic: com.eona.app.core.model.RouteTraffic? = null,
    /** Shown a few seconds after a switch to a faster route. */
    val fasterNotice: FasterRouteNotice? = null,
    /** "Ralentissement du trafic ?", asked a few seconds about a slowdown nobody knows of yet. */
    val slowdownPrompt: SlowdownPrompt? = null,
    /** Shown a few seconds once the destination is reached. */
    val arrival: TripArrival? = null,
) {
    val speedStatus: SpeedStatus?
        get() = SpeedStatus.of(speedKmh, speedLimitKmh)

    val isNavigating: Boolean get() = trip != null

    val isSearchingGps: Boolean get() = gpsSignal == GpsSignal.Searching || gpsSignal == GpsSignal.Lost
}
