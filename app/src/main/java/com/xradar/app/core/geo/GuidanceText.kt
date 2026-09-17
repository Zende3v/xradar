package com.xradar.app.core.geo

import com.xradar.app.core.model.Maneuver
import com.xradar.app.core.model.RouteStep
import kotlin.math.roundToInt

/**
 * Pure mapping from OSRM maneuvers to French instructions + a visual [Maneuver]
 * class. No Android dependency, so it moves to KMP with the rest of the logic.
 * Reference: OSRM `maneuver.type` / `maneuver.modifier` vocabulary.
 */
object GuidanceText {

    /** Which arrow to show for a step. */
    fun maneuverOf(step: RouteStep): Maneuver = when (step.type) {
        "depart" -> Maneuver.Depart
        "arrive" -> Maneuver.Arrive
        "roundabout", "rotary", "roundabout turn" -> Maneuver.Roundabout
        "merge" -> Maneuver.Merge
        // The ramp arrow points right: a ramp on the left gets a left arrow.
        "on ramp", "off ramp" -> if (step.modifier?.contains("left") == true) Maneuver.SlightLeft else Maneuver.Ramp
        "fork" -> when {
            step.modifier?.contains("left") == true -> Maneuver.ForkLeft
            step.modifier?.contains("right") == true -> Maneuver.ForkRight
            else -> Maneuver.Straight
        }
        else -> byModifier(step.modifier)
    }

    private fun byModifier(m: String?): Maneuver = when (m) {
        "left" -> Maneuver.Left
        "right" -> Maneuver.Right
        "slight left" -> Maneuver.SlightLeft
        "slight right" -> Maneuver.SlightRight
        "sharp left" -> Maneuver.SharpLeft
        "sharp right" -> Maneuver.SharpRight
        "uturn" -> Maneuver.Uturn
        else -> Maneuver.Straight
    }

    /** Short verb phrase, capitalized, no distance ("Tournez à droite"). */
    fun verb(step: RouteStep): String {
        val m = step.modifier
        return when (step.type) {
            "depart" -> "C'est parti"
            "arrive" -> "Vous êtes arrivé"
            "roundabout", "rotary", "roundabout turn" -> {
                val exit = step.exit
                if (exit != null && exit in 1..9) "Au rond-point, prenez la ${ordinal(exit)} sortie"
                else "Prenez le rond-point"
            }
            "merge" -> "Insérez-vous" + side(m)
            "on ramp" -> "Prenez la bretelle" + side(m)
            "off ramp" -> "Prenez la sortie" + side(m)
            "fork" -> when {
                m?.contains("left") == true -> "Restez à gauche"
                m?.contains("right") == true -> "Restez à droite"
                else -> "Continuez tout droit"
            }
            "end of road" -> when (m) {
                "left" -> "Au bout de la route, à gauche"
                "right" -> "Au bout de la route, à droite"
                else -> "Continuez tout droit"
            }
            "new name", "continue", "notification", "use lane" -> when (m) {
                null, "straight" -> "Continuez tout droit"
                "uturn" -> "Faites demi-tour"
                else -> turn(m)
            }
            else -> turn(m)
        }
    }

    private fun turn(m: String?): String = when (m) {
        "left" -> "Tournez à gauche"
        "right" -> "Tournez à droite"
        "slight left" -> "Serrez à gauche"
        "slight right" -> "Serrez à droite"
        "sharp left" -> "Tournez franchement à gauche"
        "sharp right" -> "Tournez franchement à droite"
        "uturn" -> "Faites demi-tour"
        else -> "Continuez tout droit"
    }

    private fun side(m: String?): String = when {
        m?.contains("left") == true -> " à gauche"
        m?.contains("right") == true -> " à droite"
        else -> ""
    }

    private fun ordinal(n: Int): String = if (n == 1) "1re" else "${n}e"

    /** Compact distance for the banner ("250 m", "1,2 km"). */
    fun distanceLabel(meters: Int): String = when {
        meters >= 1000 -> {
            val km = meters / 1000.0
            if (km >= 10) "${km.roundToInt()} km" else "%.1f km".format(km).replace('.', ',')
        }
        meters >= 20 -> "${((meters + 5) / 10) * 10} m"
        else -> "$meters m"
    }

    /** Distance as spoken French ("250 mètres", "1,2 kilomètre", "2 kilomètres"). */
    fun spokenDistance(meters: Int): String = when {
        meters >= 1000 -> {
            val km = meters / 1000.0
            val tenths = (km * 10).roundToInt()
            when {
                km >= 10 -> "${km.roundToInt()} kilomètres"
                // "1,0 kilomètre" reads badly out loud — say "1 kilomètre".
                tenths % 10 == 0 -> "${tenths / 10} kilomètre" + if (tenths >= 20) "s" else ""
                else -> "%.1f".format(km).replace('.', ',') + " kilomètre" + if (km >= 2) "s" else ""
            }
        }
        else -> "${((meters + 25) / 50).coerceAtLeast(1) * 50} mètres"
    }

    /** Full spoken heads-up ("Dans 300 mètres, tournez à droite sur Rue de la Paix"). */
    fun spokenFar(step: RouteStep, meters: Int): String {
        if (step.type == "arrive") return "Vous êtes bientôt arrivé"
        val head = "Dans ${spokenDistance(meters)}, ${lowerFirst(verb(step))}"
        val road = step.name.takeIf { it.isNotBlank() && step.type != "roundabout" && step.type != "rotary" }
        return if (road != null) "$head sur $road" else head
    }

    /** Short spoken cue at the maneuver ("Tournez à droite maintenant"). */
    fun spokenNear(step: RouteStep): String = when (step.type) {
        "arrive" -> "Vous êtes arrivé à destination"
        "roundabout", "rotary", "roundabout turn", "merge", "on ramp", "off ramp", "fork" -> verb(step)
        else -> {
            val v = verb(step)
            if (v == "Continuez tout droit") v else "$v maintenant"
        }
    }

    private fun lowerFirst(s: String): String =
        if (s.isEmpty()) s else s[0].lowercaseChar() + s.substring(1)
}
