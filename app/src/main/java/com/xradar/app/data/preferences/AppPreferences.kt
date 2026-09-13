package com.xradar.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.xradar.app.core.model.FuelType
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
    /** Share my position with nearby drivers (visible by default). */
    val liveVisible: Boolean = true,
    /** Radius (km) to see other live drivers (1..200). */
    val liveRadiusKm: Int = 20,
) {
    companion object {
        const val MIN_LIVE_KM = 1
        const val MAX_LIVE_KM = 200
    }
}

/** How the app picks its color scheme. */
enum class ThemeMode { System, Light, Dark }

/** Which basemap the map draws: follow the app theme, or force one. */
enum class MapStyle { Auto, Bright, Dark }

/** Look-and-feel and routing choices (persisted), edited from Réglages and the E3 menu. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.Dark,
    val mapStyle: MapStyle = MapStyle.Auto,
    /** Ask the router to keep the trip off toll roads. */
    val avoidTolls: Boolean = false,
    /** Ask the router to keep the trip off motorways. */
    val avoidHighways: Boolean = false,
    /** Fuel whose price the nearby "Carburant" search shows, picked there. */
    val preferredFuel: FuelType = FuelType.Gazole,
)

/** App-scoped preferences, backed by SharedPreferences. Init once from a Context. */
object AppPreferences {

    private var prefs: SharedPreferences? = null

    private val _alerts = MutableStateFlow(AlertPreferences())
    val alerts: StateFlow<AlertPreferences> = _alerts.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

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
            liveVisible = p.getBoolean("liveVisible", true),
            liveRadiusKm = p.getInt("liveRadiusKm", 20)
                .coerceIn(AlertPreferences.MIN_LIVE_KM, AlertPreferences.MAX_LIVE_KM),
        )
        _settings.value = AppSettings(
            themeMode = enumOrDefault(p.getString("themeMode", null), ThemeMode.Dark),
            mapStyle = enumOrDefault(p.getString("mapStyle", null), MapStyle.Auto),
            avoidTolls = p.getBoolean("avoidTolls", false),
            avoidHighways = p.getBoolean("avoidHighways", false),
            preferredFuel = enumOrDefault(p.getString("preferredFuel", null), FuelType.Gazole),
        )
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        prefs?.edit()?.apply {
            putString("themeMode", updated.themeMode.name)
            putString("mapStyle", updated.mapStyle.name)
            putBoolean("avoidTolls", updated.avoidTolls)
            putBoolean("avoidHighways", updated.avoidHighways)
            putString("preferredFuel", updated.preferredFuel.name)
            apply()
        }
    }

    /** Stored enum name, tolerant of a value written by an older build. */
    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

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
            putBoolean("liveVisible", updated.liveVisible)
            putInt("liveRadiusKm", updated.liveRadiusKm)
            apply()
        }
    }
}
