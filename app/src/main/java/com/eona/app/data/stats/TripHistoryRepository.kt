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
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                TripRecord(
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
                )
            }.sortedByDescending { it.startedAt }
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
        updated.forEach { t ->
            array.put(
                JSONObject().apply {
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
                },
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

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

    private companion object {
        const val KEY = "list"
        const val MAX = 200
    }
}

/** Simple aggregate for the profile screen. */
data class TripStats(
    val trips: Int,
    val kilometers: Int,
    val alerts: Int,
)
