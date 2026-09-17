package com.xradar.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.xradar.app.core.model.FuelType
import com.xradar.app.core.model.ReportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-tunable alert preferences (persisted). Edited from the HUD's "Options" dock,
 *  read by [com.xradar.app.feature.drive.DriveViewModel] to filter alerts. */
data class AlertPreferences(
    /** Official fixed speed radars. */
    val radarFixed: Boolean = true,
    /** Report categories turned off one by one ([ReportType.ALERT_OPTIONS]). Red-light radars
     *  follow the camera's switch. */
    val hiddenReports: Set<ReportType> = emptySet(),
    val sound: Boolean = true,
    val vibration: Boolean = true,
    /** Spoken (TTS) alert & maneuver announcements. */
    val voice: Boolean = true,
    /** Share my position with nearby drivers (visible by default). */
    val liveVisible: Boolean = true,
    /** Radius (km) to see other live drivers (1..200). */
    val liveRadiusKm: Int = 20,
) {
    /** Whether reports of [type] reach the driver. */
    fun shows(type: ReportType): Boolean = type !in hiddenReports

    fun toggled(type: ReportType): AlertPreferences =
        copy(hiddenReports = if (type in hiddenReports) hiddenReports - type else hiddenReports + type)

    companion object {
        const val MIN_LIVE_KM = 1
        const val MAX_LIVE_KM = 200
    }
}

/** How the app picks its color scheme. */
enum class ThemeMode { System, Light, Dark }

/** Which basemap the map draws: follow the app theme, or force one. */
enum class MapStyle { Auto, Bright, Dark }

/** Look-and-feel and routing choices (persisted), edited from Réglages and the Options dock. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.Dark,
    val mapStyle: MapStyle = MapStyle.Auto,
    /** Ask the router to keep the trip off toll roads. */
    val avoidTolls: Boolean = false,
    /** Ask the router to keep the trip off motorways. */
    val avoidHighways: Boolean = false,
    /** Ask the router to go around the traffic jams drivers reported. */
    val avoidTraffic: Boolean = false,
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
            hiddenReports = readHiddenReports(p),
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
            avoidTraffic = p.getBoolean("avoidTraffic", false),
            preferredFuel = enumOrDefault(p.getString("preferredFuel", null), FuelType.Gazole),
        )
    }

    /** The categories turned off; before one switch per category, the grouped switches of earlier builds. */
    private fun readHiddenReports(p: SharedPreferences): Set<ReportType> {
        p.getStringSet("hiddenReports", null)?.let { stored ->
            return stored.mapNotNull { ReportType.fromWire(it) }.toSet()
        }
        return buildSet {
            if (!p.getBoolean("radarMobile", true)) add(ReportType.RadarMobile)
            if (!p.getBoolean("cameras", true)) add(ReportType.Camera)
            if (!p.getBoolean("controlZones", true)) add(ReportType.ControlZone)
            if (!p.getBoolean("hazards", true)) {
                addAll(
                    listOf(
                        ReportType.StoppedVehicle, ReportType.Accident, ReportType.ObjectOnRoad,
                        ReportType.DamagedRoad, ReportType.Roadworks, ReportType.SlipperyRoad,
                        ReportType.LowVisibility, ReportType.RoadCrew, ReportType.WrongWay,
                    ),
                )
            }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        prefs?.edit()?.apply {
            putString("themeMode", updated.themeMode.name)
            putString("mapStyle", updated.mapStyle.name)
            putBoolean("avoidTolls", updated.avoidTolls)
            putBoolean("avoidHighways", updated.avoidHighways)
            putBoolean("avoidTraffic", updated.avoidTraffic)
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
            putStringSet("hiddenReports", updated.hiddenReports.map { it.wire }.toSet())
            putBoolean("sound", updated.sound)
            putBoolean("vibration", updated.vibration)
            putBoolean("voice", updated.voice)
            putBoolean("liveVisible", updated.liveVisible)
            putInt("liveRadiusKm", updated.liveRadiusKm)
            apply()
        }
    }
}
