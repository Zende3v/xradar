package com.xradar.app.feature.drive

import com.xradar.app.core.model.GeoPoint
import com.xradar.app.core.model.GpsSignal
import com.xradar.app.core.model.GuidanceInstruction
import com.xradar.app.core.model.LiveUser
import com.xradar.app.core.model.LocationSample
import com.xradar.app.core.model.Radar
import com.xradar.app.core.model.RadarZone
import com.xradar.app.core.model.RoadAlert
import com.xradar.app.core.model.SpeedStatus
import com.xradar.app.core.model.TripInfo
import com.xradar.app.core.model.UserReport

/** Everything the driving HUD needs to render one frame. */
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
    /** Other drivers sharing their position nearby. */
    val liveUsers: List<LiveUser> = emptyList(),
    /** OSM road signs near the driver. */
    val signs: List<com.xradar.app.core.model.RoadSign> = emptyList(),
    /** Active route polyline, if navigating to a destination. */
    val routePoints: List<GeoPoint> = emptyList(),
    /** Next maneuver to display, if navigating with steps available. */
    val guidance: GuidanceInstruction? = null,
    /** A destination was picked but no route came back (network / provider down). */
    val routeError: Boolean = false,
) {
    val speedStatus: SpeedStatus?
        get() = SpeedStatus.of(speedKmh, speedLimitKmh)

    val isNavigating: Boolean get() = trip != null

    val isSearchingGps: Boolean get() = gpsSignal == GpsSignal.Searching || gpsSignal == GpsSignal.Lost
}
