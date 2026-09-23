package com.eona.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.eona.app.core.geo.SunClock
import com.eona.app.core.model.FuelType
import com.eona.app.core.model.LocationSample
import com.eona.app.core.model.ReportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-tunable alert preferences (persisted). Edited from the HUD's "Options" dock,
 *  read by [com.eona.app.feature.drive.DriveViewModel] to filter alerts. */
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
    /** What warns the driver over the speed limit. */
    val overspeed: OverspeedWarning = OverspeedWarning.Voice,
    /** "Volume Guidage" (0..1): the spoken turn-by-turn and the trip's own announcements. */
    val guidanceVolume: Float = 1f,
    /** "Volume alertes" (0..1): the alert sounds and the spoken alerts (radars, dangers, overspeed). */
    val alertVolume: Float = 1f,
) {
    /** Whether reports of [type] reach the driver. */
    fun shows(type: ReportType): Boolean = type !in hiddenReports

    fun toggled(type: ReportType): AlertPreferences =
        copy(hiddenReports = if (type in hiddenReports) hiddenReports - type else hiddenReports + type)
}

/** "Dépassement limitation": the spoken warning, a beep of its own, or nothing. */
enum class OverspeedWarning { Voice, Beep, Off }

/**
 * "Thème général", for the whole app, the map and the HUD over it: "Auto" follows day and night
 * where the driver is, "Jour" and "Nuit" pin it.
 */
enum class AppTheme {
    Auto, Day, Night;

    /** Whether the app draws at night: "Auto" follows the sky where the driver is (Paris's
     *  before the first fix). */
    fun isDark(location: LocationSample?, epochMillis: Long = System.currentTimeMillis()): Boolean = when (this) {
        Auto -> !SunClock.isDaylight(location?.latitude ?: PARIS_LAT, location?.longitude ?: PARIS_LON, epochMillis)
        Day -> false
        Night -> true
    }

    private companion object {
        const val PARIS_LAT = 48.8566
        const val PARIS_LON = 2.3522
    }
}

/**
 * The colour the driver picked for everything interactive: buttons, the route, the arrow.
 * Stored as its hex, so a colour added later needs no migration.
 */
enum class AccentColor(val hex: String, val label: String) {
    Cyan("2CD5E0", "Cyan"),
    Coral("FF5E36", "Corail"),
    Lemon("FFF342", "Citron"),
    Lime("CFFF2B", "Citron vert"),
    Mint("A8FFD8", "Menthe"),
    Turquoise("52FFEC", "Turquoise"),
    Azure("009EFF", "Azur"),
    Lavender("856EFF", "Lavande"),
    Indigo("4E21FF", "Indigo"),
    Violet("8500FF", "Violet"),
    Magenta("E100FF", "Magenta");

    /** The colour itself, 0xRRGGBB. */
    val rgb: Int get() = hex.toInt(16)

    companion object {
        fun fromHex(hex: String?): AccentColor = entries.firstOrNull { it.hex == hex } ?: Cyan
    }
}

/** Look-and-feel and routing choices (persisted), edited from Réglages and the Options dock. */
data class AppSettings(
    val theme: AppTheme = AppTheme.Auto,
    /** "Couleur de l'app". */
    val accent: AccentColor = AccentColor.Cyan,
    /** Ask the router to keep the trip off toll roads. */
    val avoidTolls: Boolean = false,
    /** Ask the router to keep the trip off motorways. */
    val avoidHighways: Boolean = false,
    /** "Éviter les bouchons": a faster way around the traffic ahead, taken only when it saves
     *  enough time (or goes around a closed road). */
    val avoidTraffic: Boolean = false,
    /** Fuel whose price the nearby "Carburant" search shows, picked there. */
    val preferredFuel: FuelType = FuelType.Gazole,
    /** "Proche uniquement" in the nearby "Carburant" search: the nearest open stations, no price. */
    val fuelNearestOnly: Boolean = false,
    // Confidentialité.
    /** "Aide au trafic partagé": a slowdown on a fast road is sent anonymously to the shared
     *  traffic (and may ask "Ralentissement du trafic ?"). Off: nothing of this driver feeds it. */
    val sharedTraffic: Boolean = true,
    /** "Suggestions de trajets": the destinations picked are kept on the phone and offered again
     *  in the search ("Récents"). */
    val tripSuggestions: Boolean = true,
    /** "Statistiques de conduite": trips and driving time are recorded and sent to the account. */
    val drivingStats: Boolean = true,
    /** "Présence et position": the backend counts the app open and a trip running, and the
     *  EONA team sees where this driver is. Off unless the driver turns it on. */
    val presence: Boolean = false,
    /** "Temps d'utilisation": the time spent with the app open adds up on the account. */
    val usageTime: Boolean = true,
    /** The version of the terms the driver accepted, and when. Empty: never accepted. */
    val termsVersion: String = "",
    val termsAcceptedAt: Long? = null,
    /** True when the driver refused the terms: the app stays closed until they change their mind. */
    val termsDeclined: Boolean = false,
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
            overspeed = enumOrDefault(p.getString("overspeed", null), OverspeedWarning.Voice),
            guidanceVolume = p.getFloat("guidanceVolume", 1f).coerceIn(0f, 1f),
            alertVolume = p.getFloat("alertVolume", 1f).coerceIn(0f, 1f),
        )
        _settings.value = AppSettings(
            theme = enumOrDefault(p.getString("theme", null), legacyTheme(p)),
            accent = AccentColor.fromHex(p.getString("accent", null)),
            avoidTolls = p.getBoolean("avoidTolls", false),
            avoidHighways = p.getBoolean("avoidHighways", false),
            avoidTraffic = p.getBoolean("avoidTraffic", false),
            preferredFuel = enumOrDefault(p.getString("preferredFuel", null), FuelType.Gazole),
            fuelNearestOnly = p.getBoolean("fuelNearestOnly", false),
            // Stored under its first name: the choice made before the rename stays.
            sharedTraffic = p.getBoolean("shareSlowdowns", true),
            tripSuggestions = p.getBoolean("tripSuggestions", true),
            drivingStats = p.getBoolean("drivingStats", true),
            presence = p.getBoolean("presence", false),
            usageTime = p.getBoolean("usageTime", true),
            termsVersion = p.getString("termsVersion", null).orEmpty(),
            termsAcceptedAt = p.getLong("termsAcceptedAt", 0L).takeIf { it > 0L },
            termsDeclined = p.getBoolean("termsDeclined", false),
        )
    }

    /** The driver accepted [version] of the terms, now. Any earlier refusal is forgotten. */
    fun acceptTerms(version: String) {
        updateSettings { it.copy(termsVersion = version, termsAcceptedAt = System.currentTimeMillis(), termsDeclined = false) }
    }

    /** The driver refused: nothing that needs the terms starts, and the screen says why. */
    fun declineTerms() {
        updateSettings { it.copy(termsVersion = "", termsAcceptedAt = null, termsDeclined = true) }
    }

    /**
     * Whether the terms must be shown: never accepted, refused, or a version that has to be
     * agreed to again (a typo fixed in 1.0.1 does not ask anyone a second time).
     */
    fun needsTerms(settings: AppSettings, required: String): Boolean {
        if (settings.termsDeclined) return true
        if (settings.termsVersion.isEmpty()) return true
        return settings.termsVersion.substringBefore('.') != required.substringBefore('.')
    }

    /** Before "Thème général": the basemap setting was what the drive showed, so it decides. */
    private fun legacyTheme(p: SharedPreferences): AppTheme = when (p.getString("mapStyle", null)) {
        "Bright" -> AppTheme.Day
        "Dark" -> AppTheme.Night
        else -> AppTheme.Auto
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
            putString("theme", updated.theme.name)
            putString("accent", updated.accent.hex)
            // The app theme and the basemap of earlier builds, merged into [theme].
            remove("themeMode")
            remove("mapStyle")
            putBoolean("avoidTolls", updated.avoidTolls)
            putBoolean("avoidHighways", updated.avoidHighways)
            putBoolean("avoidTraffic", updated.avoidTraffic)
            putString("preferredFuel", updated.preferredFuel.name)
            putBoolean("fuelNearestOnly", updated.fuelNearestOnly)
            putBoolean("shareSlowdowns", updated.sharedTraffic)
            putBoolean("tripSuggestions", updated.tripSuggestions)
            putBoolean("drivingStats", updated.drivingStats)
            putBoolean("presence", updated.presence)
            putBoolean("usageTime", updated.usageTime)
            putString("termsVersion", updated.termsVersion)
            putLong("termsAcceptedAt", updated.termsAcceptedAt ?: 0L)
            putBoolean("termsDeclined", updated.termsDeclined)
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
            putString("overspeed", updated.overspeed.name)
            putFloat("guidanceVolume", updated.guidanceVolume)
            putFloat("alertVolume", updated.alertVolume)
            apply()
        }
    }
}
