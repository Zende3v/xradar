package com.xradar.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-tunable alert preferences (persisted). Edited from the trip menu (E1),
 *  read by [com.xradar.app.feature.drive.DriveViewModel] to filter alerts. */
data class AlertPreferences(
    val radarFixed: Boolean = true,
    val radarMobile: Boolean = true,
    val cameras: Boolean = true,
    val controlZones: Boolean = true,
    val hazards: Boolean = true,
    val sound: Boolean = true,
    val vibration: Boolean = true,
    /** Spoken (TTS) alert & maneuver announcements. */
    val voice: Boolean = true,
    /** How far around the driver alerts are loaded, in km (20..1000). */
    val alertRadiusKm: Int = 90,
    /** Share my position with nearby drivers (visible by default). */
    val liveVisible: Boolean = true,
    /** Radius (km) to see other live drivers (1..200). */
    val liveRadiusKm: Int = 20,
) {
    companion object {
        const val MIN_RADIUS_KM = 20
        const val MAX_RADIUS_KM = 1000
        const val MIN_LIVE_KM = 1
        const val MAX_LIVE_KM = 200
    }
}

/** App-scoped preferences, backed by SharedPreferences. Init once from a Context. */
object AppPreferences {

    private var prefs: SharedPreferences? = null

    private val _alerts = MutableStateFlow(AlertPreferences())
    val alerts: StateFlow<AlertPreferences> = _alerts.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("xr_prefs", Context.MODE_PRIVATE)
        prefs = p
        _alerts.value = AlertPreferences(
            radarFixed = p.getBoolean("radarFixed", true),
            radarMobile = p.getBoolean("radarMobile", true),
            cameras = p.getBoolean("cameras", true),
            controlZones = p.getBoolean("controlZones", true),
            hazards = p.getBoolean("hazards", true),
            sound = p.getBoolean("sound", true),
            vibration = p.getBoolean("vibration", true),
            voice = p.getBoolean("voice", true),
            alertRadiusKm = p.getInt("alertRadiusKm", 90)
                .coerceIn(AlertPreferences.MIN_RADIUS_KM, AlertPreferences.MAX_RADIUS_KM),
            liveVisible = p.getBoolean("liveVisible", true),
            liveRadiusKm = p.getInt("liveRadiusKm", 20)
                .coerceIn(AlertPreferences.MIN_LIVE_KM, AlertPreferences.MAX_LIVE_KM),
        )
    }

    fun updateAlerts(transform: (AlertPreferences) -> AlertPreferences) {
        val updated = transform(_alerts.value)
        _alerts.value = updated
        prefs?.edit()?.apply {
            putBoolean("radarFixed", updated.radarFixed)
            putBoolean("radarMobile", updated.radarMobile)
            putBoolean("cameras", updated.cameras)
            putBoolean("controlZones", updated.controlZones)
            putBoolean("hazards", updated.hazards)
            putBoolean("sound", updated.sound)
            putBoolean("vibration", updated.vibration)
            putBoolean("voice", updated.voice)
            putInt("alertRadiusKm", updated.alertRadiusKm)
            putBoolean("liveVisible", updated.liveVisible)
            putInt("liveRadiusKm", updated.liveRadiusKm)
            apply()
        }
    }
}
