package com.eona.app.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/** The words the nearby list shows for a place: its opening state and its details. Pure Kotlin. */
object NearbyLabels {

    enum class Tone { Positive, Warning, Negative, Neutral }

    /** "Ouvert" + "07:00–21:00", "Fermé" + "ouvre demain à 07:00"… */
    data class Status(val text: String, val detail: String?, val tone: Tone)

    /** An open place closing within this is "Ferme bientôt". */
    const val CLOSING_SOON_MS = 30L * 60 * 1000

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.FRANCE)

    /**
     * The state at [nowMillis]. The backend read it when the list loaded; once its next change
     * has passed the state flips, so a list left open does not keep saying "Ouvert".
     */
    fun state(hours: OpeningHours, nowMillis: Long): OpenState {
        val next = hours.nextChangeMillis ?: return hours.state
        if (nowMillis < next) return hours.state
        return when (hours.state) {
            OpenState.Open -> OpenState.Closed
            OpenState.Closed -> OpenState.Open
            OpenState.Unknown -> OpenState.Unknown
        }
    }

    fun status(hours: OpeningHours?, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Status? {
        hours ?: return null
        if (hours.alwaysOpen) return Status("Ouvert", "24 h/24", Tone.Positive)
        val stale = hours.nextChangeMillis != null && nowMillis >= hours.nextChangeMillis
        return when (state(hours, nowMillis)) {
            OpenState.Open -> {
                val next = hours.nextChangeMillis.takeUnless { stale }
                if (next != null && next - nowMillis <= CLOSING_SOON_MS) {
                    Status("Ferme bientôt", "à ${clock(next, zone)}", Tone.Warning)
                } else {
                    Status("Ouvert", slots(hours.today), Tone.Positive)
                }
            }
            OpenState.Closed -> {
                val next = hours.nextChangeMillis.takeUnless { stale }
                Status("Fermé", next?.let { opensAt(it, nowMillis, zone) }, Tone.Negative)
            }
            OpenState.Unknown -> slots(hours.today)?.let { Status("Horaires", it, Tone.Neutral) }
        }
    }

    /** "21:00" on the phone's clock. */
    private fun clock(atMillis: Long, zone: ZoneId): String = CLOCK.format(Instant.ofEpochMilli(atMillis).atZone(zone))

    /** "07:00–12:00, 14:00–19:00"; "24 h/24" for the whole day; null when there is none. */
    fun slots(today: List<TimeSlot>): String? {
        if (today.isEmpty()) return null
        if (today.any { it.from == "00:00" && it.to == "24:00" }) return "24 h/24"
        return today.joinToString(", ") { "${it.from}–${it.to}" }
    }

    /** "ouvre à 14:00", "ouvre demain à 07:00", "ouvre lun. à 07:00". */
    fun opensAt(atMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(atMillis).atZone(zone)
        val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate(), at.toLocalDate())
        val time = CLOCK.format(at)
        return when (days) {
            0L -> "ouvre à $time"
            1L -> "ouvre demain à $time"
            else -> "ouvre ${WEEKDAY.format(at)} à $time"
        }
    }

    /**
     * What matters for the place's kind, in reading order: the brand of a station (unless
     * its name already says it), a charger's power and connectors, a car park's fee, type
     * and size, a hotel's stars, and "Clients" when it is for customers only.
     */
    fun details(place: Place): List<String> {
        val info = place.nearby ?: return emptyList()
        val out = mutableListOf<String>()
        info.brand?.takeIf { !flat(place.name).contains(flat(it)) }?.let(out::add)
        info.charging?.let { c ->
            c.maxKw?.let { out += kilowatts(it) }
            out += c.connectors
            c.points?.let { out += if (it == 1) "1 point" else "$it points" }
        }
        info.parking?.let { p ->
            when (p.fee) {
                true -> out += "Payant"
                false -> out += "Gratuit"
                null -> Unit
            }
            if (p.parkAndRide) out += "Parking relais"
            when (p.type) {
                ParkingType.Underground -> out += "Souterrain"
                ParkingType.MultiStorey -> out += "Silo"
                ParkingType.Rooftop -> out += "Sur le toit"
                ParkingType.StreetSide -> out += "Bord de rue"
                ParkingType.Surface, null -> Unit
            }
            p.capacity?.let { out += if (it == 1) "1 place" else "$it places" }
        }
        info.stars?.let { out += "★".repeat(it) }
        if (info.customersOnly) out += "Clients"
        return out
    }

    /** "150 kW", "7,4 kW". */
    fun kilowatts(kw: Double): String =
        if (kw == kw.roundToInt().toDouble()) "${kw.roundToInt()} kW" else "%.1f kW".format(Locale.FRANCE, kw)

    /** "850 m" / "12,4 km" — the driver reads a distance, not a number of metres. */
    fun distance(meters: Int): String = when {
        meters < 1000 -> "$meters m"
        meters < 10_000 -> "%.1f km".format(Locale.FRANCE, meters / 1000.0)
        else -> "${(meters / 1000.0).roundToInt()} km"
    }

    private fun flat(value: String): String = value.lowercase(Locale.FRANCE).filter { it.isLetterOrDigit() }
}
