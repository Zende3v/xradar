package com.eona.app.core.model

import com.eona.app.core.drive.TripProgress
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** Choix d'itinéraire (iOS 02/10). Valeur brute échangée avec le backend (`preference`). */
enum class RoutePreference(val wire: String) {
    /** Rapide : le plus rapide, bouchons évités en route. */
    Fastest("fastest"),
    /** Éco : le plus court en distance. */
    Shortest("shortest");

    companion object {
        fun fromWire(value: String?): RoutePreference? = entries.firstOrNull { it.wire == value }
    }
}

/** Une option du choix : en calcul, prête, ou indisponible (échec, backend sans Éco). */
sealed interface RouteOption {
    val route: Route? get() = null

    data object Loading : RouteOption
    data class Ready(override val route: Route) : RouteOption
    data object Unavailable : RouteOption
}

/** Choix d'itinéraire vers [destination] : Rapide, Éco, choix retenu. Copie de RouteChoice.swift. */
data class RouteChoice(
    val destination: Place,
    val fastest: RouteOption = RouteOption.Loading,
    val shortest: RouteOption = RouteOption.Loading,
    val selected: RoutePreference = RoutePreference.Fastest,
) {
    fun option(preference: RoutePreference): RouteOption = when (preference) {
        RoutePreference.Fastest -> fastest
        RoutePreference.Shortest -> shortest
    }

    /** Route du choix retenu, prête ; null sinon. */
    val chosenRoute: Route? get() = option(selected).route

    /** Aucune route obtenue : erreur, nouvel essai proposé. */
    val failed: Boolean get() = fastest == RouteOption.Unavailable && shortest == RouteOption.Unavailable

    /** Calcul encore en cours. */
    val loading: Boolean get() = fastest == RouteOption.Loading || shortest == RouteOption.Loading

    /**
     * Rapide = moins de temps avec trafic. Valhalla choisit Rapide sans trafic : Éco chronométré
     * plus vite par HERE (les deux temps HERE connus) prend aussi place de Rapide. Aucun appel en plus.
     */
    fun keepFastestByTraffic(): RouteChoice {
        val fast = fastest.route ?: return this
        val eco = shortest.route ?: return this
        val fastSeconds = fast.trafficSeconds ?: return this
        val ecoSeconds = eco.trafficSeconds ?: return this
        return if (ecoSeconds < fastSeconds) copy(fastest = RouteOption.Ready(eco)) else this
    }

    /** Option retenue indisponible : choix passe sur l'autre, prête. Sinon inchangé. */
    fun keepUsableSelection(): RouteChoice {
        if (option(selected) != RouteOption.Unavailable) return this
        val other = RoutePreference.entries.firstOrNull { option(it).route != null } ?: return this
        return copy(selected = other)
    }
}

/** Temps annoncé : HERE avec trafic quand connu, sinon moteur. */
val Route.expectedSeconds: Int get() = trafficSeconds ?: durationSeconds

/**
 * Coût carburant estimé d'un trajet : consommation des réglages, prix médian des stations
 * proches pour le carburant préféré.
 */
data class FuelEstimate(val litresPer100: Double, val eurosPerLitre: Double) {
    /** Euros pour [meters]. */
    fun cost(meters: Int): Double = meters.coerceAtLeast(0) / 100_000.0 * litresPer100 * eurosPerLitre

    companion object {
        /** Prix médian de [fuel] parmi [places] : en vente, mis à jour sous 96 h. Null sans prix. */
        fun medianPrice(fuel: FuelType, places: List<Place>, nowMillis: Long): Double? {
            val prices = places.mapNotNull { it.shownFuelPrice(fuel, nowMillis) }.sorted()
            if (prices.isEmpty()) return null
            val middle = prices.size / 2
            return if (prices.size % 2 == 0) (prices[middle - 1] + prices[middle]) / 2 else prices[middle]
        }
    }
}

/** Textes du choix d'itinéraire, mot pour mot comme iOS (RouteChoiceText). */
object RouteChoiceText {
    /** Écart sous lequel Éco et Rapide sont un même trajet : 1 % de la distance, 100 m au moins. */
    private const val SAME_ROUTE_SHARE = 0.01
    private const val SAME_ROUTE_MIN_METERS = 100

    /** "8 min", "1 h 05". Jamais "0 min". */
    fun duration(seconds: Int): String {
        val minutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)
        return if (minutes >= 60) "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}" else "$minutes min"
    }

    /** "24,8 km", "850 m". */
    fun distance(meters: Int): String = TripProgress.distanceLabel(meters.toDouble())

    /** Heure d'arrivée "18:42" en partant à [nowMillis]. */
    fun arrival(route: Route, nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(nowMillis + route.expectedSeconds * 1000L).atZone(zone).format(HHMM)

    /** Éco et Rapide au même tracé, à l'arrondi près. */
    fun same(eco: Route, fastest: Route): Boolean {
        val gap = abs(eco.distanceMeters - fastest.distanceMeters)
        return gap <= maxOf(SAME_ROUTE_MIN_METERS, (fastest.distanceMeters * SAME_ROUTE_SHARE).toInt())
    }

    /** "4,20 €". */
    fun euros(value: Double): String =
        BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " €"

    /** Ligne sous Éco, face à Rapide : temps en plus, km et euros économisés, "+4 min · −3,2 km · −0,85 €". */
    fun eco(eco: Route, fastest: Route?, fuel: FuelEstimate? = null): String {
        if (fastest == null) {
            return fuel?.let { "Le plus court · ≈ ${euros(it.cost(eco.distanceMeters))}" } ?: "Le plus court en distance"
        }
        if (same(eco, fastest)) return "Même trajet que Rapide"
        val minutes = ((eco.expectedSeconds - fastest.expectedSeconds) / 60.0).roundToInt()
        // Temps de sources différentes (HERE muet pour l'un) : écart dit tel quel.
        val parts = mutableListOf(
            when {
                minutes >= 1 -> "+$minutes min"
                minutes <= -1 -> "−${-minutes} min"
                else -> "Aussi rapide"
            },
        )
        val saved = fastest.distanceMeters - eco.distanceMeters
        if (saved > 0) {
            parts += "−${distance(saved)}"
            if (fuel != null && fuel.cost(saved) >= 0.005) parts += "−${euros(fuel.cost(saved))}"
        }
        return parts.joinToString(" · ")
    }

    /** Étapes du choix : "Via Boulangerie", "Via Boulangerie +2". */
    fun via(names: List<String>): String {
        val first = names.firstOrNull() ?: return ""
        return if (names.size > 1) "Via $first +${names.size - 1}" else "Via $first"
    }

    /** Bandeau en route : "1 étape · Boulangerie", "3 étapes · Boulangerie" (la prochaine). */
    fun stops(names: List<String>): String {
        val next = names.firstOrNull() ?: return "Étape"
        return "${names.size} étape${if (names.size > 1) "s" else ""} · $next"
    }

    /** Ligne sous Rapide, face à Éco : temps gagné, coût estimé, "6 min gagnées · ≈ 4,20 €". */
    fun fastest(fastest: Route, eco: Route?, fuel: FuelEstimate? = null): String {
        var minutes = 0
        if (eco != null && !same(eco, fastest)) minutes = ((eco.expectedSeconds - fastest.expectedSeconds) / 60.0).roundToInt()
        val gained = if (minutes >= 1) "$minutes min gagnée${if (minutes > 1) "s" else ""}" else null
        if (fuel == null) return gained ?: "Bouchons évités en route"
        return "${gained ?: "Bouchons évités"} · ≈ ${euros(fuel.cost(fastest.distanceMeters))}"
    }

    /** Ce que traverse la route : "Autoroute · Péage", "Sans autoroute ni péage" ; null inconnu. */
    fun roads(roads: RouteRoads?): String? {
        roads ?: return null
        val parts = listOfNotNull(
            "Autoroute".takeIf { roads.motorway },
            "Péage".takeIf { roads.toll },
            "Ferry".takeIf { roads.ferry },
        )
        return if (parts.isEmpty()) "Sans autoroute ni péage" else parts.joinToString(" · ")
    }

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
