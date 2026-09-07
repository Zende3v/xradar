package com.xradar.app.data.recents

import android.content.Context
import com.xradar.app.core.model.Place
import com.xradar.app.core.model.PlaceKind
import org.json.JSONArray
import org.json.JSONObject

/** Persists recently chosen destinations (SharedPreferences + JSON), newest first. */
class RecentsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("xr_recents", Context.MODE_PRIVATE)

    fun recents(): List<Place> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Place(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    subtitle = o.optString("subtitle"),
                    kind = PlaceKind.Recent,
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(place: Place) {
        val updated = (listOf(place) + recents().filter { it.id != place.id }).take(MAX)
        val array = JSONArray()
        updated.forEach { p ->
            array.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("subtitle", p.subtitle)
                    put("lat", p.lat)
                    put("lon", p.lon)
                },
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "list"
        const val MAX = 8
    }
}
