package com.xradar.app.data.stats

import android.content.Context
import com.xradar.app.core.model.TripRecord
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
                )
            }.sortedByDescending { it.startedAt }
        }.getOrDefault(emptyList())
    }

    fun add(trip: TripRecord) {
        val updated = (listOf(trip) + all()).take(MAX)
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
                },
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
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
