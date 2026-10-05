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

/**
 * "Véhicule", dans Réglages : le dessin de la position sur la carte. Gardé sur le téléphone,
 * stocké en [wire] ("car", "motorcycle", "taxi", "truck", comme iOS) ; inconnu = flèche.
 * [moped] : 45 km/h, sans voie rapide (itinéraire, limites, alertes) ; autres : curseur seul.
 */
enum class VehicleType(val wire: String, val label: String, val moped: Boolean = false) {
    Arrow("arrow", "Flèche"),
    Car("car", "Voiture"),
    Motorcycle("motorcycle", "Moto"),
    Taxi("taxi", "Taxi"),
    Truck("truck", "Camion");

    companion object {
        fun fromWire(wire: String?): VehicleType = entries.firstOrNull { it.wire == wire } ?: Arrow
    }
}

/** Consommation par défaut, L/100 km : valeur de départ choisie, pas mesurée (iOS, 6,5). */
const val DEFAULT_CONSUMPTION = 6.5
val CONSUMPTION_RANGE = 1.0..30.0

/** Look-and-feel and routing choices (persisted), edited from Réglages and the Options dock. */
data class AppSettings(
    val theme: AppTheme = AppTheme.Auto,
    /** "Couleur de l'app". */
    val accent: AccentColor = AccentColor.Cyan,
    /** "Véhicule": the position cursor's drawing. */
    val vehicleType: VehicleType = VehicleType.Arrow,
    /** Ask the router to keep the trip off toll roads. */
    val avoidTolls: Boolean = false,
    /** Ask the router to keep the trip off motorways. */
    val avoidHighways: Boolean = false,
    /** Ask the router to keep the trip off ferries (D4.2). */
    val avoidFerries: Boolean = false,
    /** Fuel whose price the nearby "Carburant" search shows, picked there. */
    val preferredFuel: FuelType = FuelType.Gazole,
    /** "Proche uniquement" in the nearby "Carburant" search: the nearest open stations, no price. */
    val fuelNearestOnly: Boolean = false,
    /** « Consommation », L/100 km : coût estimé des trajets. 1,0 à 30,0, cran 0,1. */
    val consumption: Double = DEFAULT_CONSUMPTION,
    /** « Permis probatoire » : limitations jeune conducteur affichées et alertes (ProbationaryLimits). */
    val probationary: Boolean = false,
    /** « Protection pluie » : écran verrouillé dès 15 km/h, levé sous 10 ou GPS perdu. */
    val rainLock: Boolean = false,
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
     *  EONA team sees where this driver is. Turned on once for everyone (iOS build 28,
     *  presenceOnByDefault); a refusal after that stays. */
    val presence: Boolean = true,
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
        // Activée d'office une fois (iOS build 28), même coupée avant ; un refus ensuite reste.
        if (!p.contains("presenceOnByDefault")) {
            p.edit().putBoolean("presence", true).putBoolean("presenceOnByDefault", true).apply()
        }
        _settings.value = AppSettings(
            theme = enumOrDefault(p.getString("theme", null), legacyTheme(p)),
            accent = AccentColor.fromHex(p.getString("accent", null)),
            vehicleType = VehicleType.fromWire(p.getString("vehicleType", null)),
            avoidTolls = p.getBoolean("avoidTolls", false),
            avoidHighways = p.getBoolean("avoidHighways", false),
            avoidFerries = p.getBoolean("avoidFerries", false),
            preferredFuel = enumOrDefault(p.getString("preferredFuel", null), FuelType.Gazole),
            fuelNearestOnly = p.getBoolean("fuelNearestOnly", false),
            consumption = p.getFloat("consumption", DEFAULT_CONSUMPTION.toFloat()).toDouble()
                .takeIf { it in CONSUMPTION_RANGE }?.let { Math.round(it * 10) / 10.0 } ?: DEFAULT_CONSUMPTION,
            probationary = p.getBoolean("probationary", false),
            rainLock = p.getBoolean("rainLock", false),
            // Stored under its first name: the choice made before the rename stays.
            sharedTraffic = p.getBoolean("shareSlowdowns", true),
            tripSuggestions = p.getBoolean("tripSuggestions", true),
            drivingStats = p.getBoolean("drivingStats", true),
            presence = p.getBoolean("presence", true),
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
            putString("vehicleType", updated.vehicleType.wire)
            // The app theme and the basemap of earlier builds, merged into [theme].
            remove("themeMode")
            remove("mapStyle")
            putBoolean("avoidTolls", updated.avoidTolls)
            putBoolean("avoidHighways", updated.avoidHighways)
            putBoolean("avoidFerries", updated.avoidFerries)
            putString("preferredFuel", updated.preferredFuel.name)
            putBoolean("fuelNearestOnly", updated.fuelNearestOnly)
            putFloat("consumption", updated.consumption.toFloat())
            putBoolean("probationary", updated.probationary)
            putBoolean("rainLock", updated.rainLock)
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
