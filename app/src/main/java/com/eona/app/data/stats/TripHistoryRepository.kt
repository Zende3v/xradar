package com.eona.app.data.stats

import android.content.Context
import com.eona.app.core.model.TripGroupRank
import com.eona.app.core.model.TripGroupResult
import com.eona.app.core.model.TripRecord
import com.eona.app.data.account.AccountApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local, on-device trip history (SharedPreferences + JSON), newest first. Guests
 * keep everything here only — nothing is synced to the cloud (deleting the app
 * loses it, by design). A cloud sync for members can be layered on later.
 */
class TripHistoryRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("xr_trips", Context.MODE_PRIVATE)

    fun all(): List<TripRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i -> fromJson(array.getJSONObject(i)) }.sortedByDescending { it.startedAt }
        }.getOrDefault(emptyList())
    }

    fun add(trip: TripRecord) {
        save((listOf(trip) + all()).take(MAX))
    }

    /** The group trip ended: its ranking joins the trip already saved. Nothing else moves. */
    fun attach(group: TripGroupResult, tripId: String) {
        val trips = all()
        if (trips.none { it.id == tripId }) return
        save(trips.map { if (it.id == tripId) it.copy(group = group) else it })
    }

    /** The group rankings kept here, by trip id: the server's history has none. */
    fun groupResults(): Map<String, TripGroupResult> =
        all().mapNotNull { trip -> trip.group?.let { trip.id to it } }.toMap()

    private fun save(updated: List<TripRecord>) {
        val array = JSONArray()
        updated.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    /** The account is deleted: its trips go with it. */
    fun removeAll() {
        prefs.edit().remove(KEY).apply()
    }

    /** Aggregate stats over all local trips. */
    fun stats(): TripStats {
        val trips = all()
        return TripStats(
            trips = trips.size,
            kilometers = trips.sumOf { it.distanceMeters } / 1000,
            alerts = trips.sumOf { it.alertsCount },
        )
    }

    companion object {
        private const val KEY = "list"
        private const val MAX = 200

        /** Un trajet en JSON, comme le garde l'historique et la file d'envoi. */
        fun toJson(t: TripRecord): JSONObject = JSONObject().apply {
            put("id", t.id)
            put("startedAt", t.startedAt)
            put("fromLabel", t.fromLabel)
            put("toLabel", t.toLabel)
            put("distanceMeters", t.distanceMeters)
            put("durationSeconds", t.durationSeconds)
            put("alertsCount", t.alertsCount)
            put("topSpeedKmh", t.topSpeedKmh)
            t.plannedSeconds?.let { put("plannedSeconds", it) }
            put("stops", t.stops)
            put("stoppedSeconds", t.stoppedSeconds)
            put("events", AccountApi.wireEvents(t))
            t.group?.let { put("group", groupJson(it)) }
            t.measure?.let { AccountApi.putMeasure(this, it) }
        }

        /** Le trajet relu ; champs absents des anciennes versions à leur valeur par défaut. */
        fun fromJson(o: JSONObject): TripRecord = TripRecord(
            id = o.getString("id"),
            startedAt = o.getLong("startedAt"),
            fromLabel = o.optString("fromLabel"),
            toLabel = o.optString("toLabel"),
            distanceMeters = o.getInt("distanceMeters"),
            durationSeconds = o.getInt("durationSeconds"),
            alertsCount = o.optInt("alertsCount"),
            topSpeedKmh = o.optInt("topSpeedKmh"),
            // Absent from trips saved before these details were recorded.
            plannedSeconds = if (o.has("plannedSeconds") && !o.isNull("plannedSeconds")) o.optInt("plannedSeconds") else null,
            stops = o.optInt("stops"),
            stoppedSeconds = o.optInt("stoppedSeconds"),
            events = AccountApi.parseEvents(o.optJSONObject("events")),
            group = o.optJSONObject("group")?.let(::parseGroup),
            measure = AccountApi.parseMeasure(o),
        )

        private fun groupJson(g: TripGroupResult) = JSONObject().apply {
            put("code", g.code)
            g.myRank?.let { put("myRank", it) }
            put(
                "ranking",
                JSONArray().apply {
                    g.ranking.forEach { r ->
                        put(
                            JSONObject().apply {
                                put("name", r.name)
                                r.rank?.let { put("rank", it) }
                                r.durationSeconds?.let { put("durationSeconds", it) }
                                r.distanceMeters?.let { put("distanceMeters", it) }
                                put("me", r.me)
                            },
                        )
                    }
                },
            )
        }

        private fun parseGroup(o: JSONObject): TripGroupResult {
            val list = o.optJSONArray("ranking") ?: JSONArray()
            return TripGroupResult(
                code = o.optString("code"),
                myRank = if (o.has("myRank")) o.optInt("myRank") else null,
                ranking = (0 until list.length()).mapNotNull { i ->
                    val r = list.optJSONObject(i) ?: return@mapNotNull null
                    TripGroupRank(
                        name = r.optString("name"),
                        rank = if (r.has("rank")) r.optInt("rank") else null,
                        durationSeconds = if (r.has("durationSeconds")) r.optInt("durationSeconds") else null,
                        distanceMeters = if (r.has("distanceMeters")) r.optInt("distanceMeters") else null,
                        me = r.optBoolean("me"),
                    )
                },
            )
        }
    }
}

/**
 * Trajets à envoyer, gardés jusqu'à réception par le serveur, et instantané du trajet en cours,
 * repris au lancement si l'app a été fermée en route. SharedPreferences + JSON. Chaque trajet garde
 * son compte : jamais envoyé à un autre. Mêmes règles qu'iOS.
 */
class TripOutbox(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("xr_trips_outbox", Context.MODE_PRIVATE)

    /** Un trajet en attente d'envoi, et le compte qui l'a roulé. */
    data class Pending(val accountId: String, val trip: TripRecord)

    fun pending(): List<Pending> {
        val raw = prefs.getString(QUEUE, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i -> parse(array.optJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    /** [trip] à envoyer pour [accountId] ; même identifiant déjà en file : remplacé. */
    fun add(trip: TripRecord, accountId: String) {
        save((listOf(Pending(accountId, trip)) + pending().filter { it.trip.id != trip.id }).take(LIMIT))
    }

    /** Reçu par le serveur. */
    fun remove(tripId: String) {
        save(pending().filter { it.trip.id != tripId })
    }

    /** Instantané du trajet en cours ; null l'efface. */
    fun keepRunning(trip: TripRecord?, accountId: String?) {
        if (trip == null || accountId == null) {
            prefs.edit().remove(RUNNING).apply()
            return
        }
        prefs.edit().putString(RUNNING, json(Pending(accountId, trip)).toString()).apply()
    }

    /** Instantané laissé par une app fermée en route, retiré de la mémoire du téléphone. */
    fun takeRunning(): Pending? {
        val raw = prefs.getString(RUNNING, null)
        prefs.edit().remove(RUNNING).apply()
        return raw?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
    }

    private fun save(updated: List<Pending>) {
        val array = JSONArray()
        updated.forEach { array.put(json(it)) }
        prefs.edit().putString(QUEUE, array.toString()).apply()
    }

    private fun json(p: Pending) = JSONObject().put("accountId", p.accountId).put("trip", TripHistoryRepository.toJson(p.trip))

    private fun parse(o: JSONObject?): Pending? {
        val trip = o?.optJSONObject("trip") ?: return null
        val accountId = o.optString("accountId").ifBlank { return null }
        return runCatching { Pending(accountId, TripHistoryRepository.fromJson(trip)) }.getOrNull()
    }

    private companion object {
        const val QUEUE = "queue"
        const val RUNNING = "running"
        /** File bornée : au-delà, les plus anciens partent. */
        const val LIMIT = 50
    }
}

/** Simple aggregate for the profile screen. */
data class TripStats(
    val trips: Int,
    val kilometers: Int,
    val alerts: Int,
)
