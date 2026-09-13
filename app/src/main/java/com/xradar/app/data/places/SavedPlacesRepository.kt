package com.xradar.app.data.places

import android.content.Context
import com.xradar.app.core.model.Place
import com.xradar.app.core.model.PlaceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A trip kept as a favourite: where to, and optionally where from (simulated start). */
data class FavoriteTrip(
    val to: Place,
    val from: Place? = null,
) {
    val id: String get() = if (from == null) to.id else "${from.id}>${to.id}"
}

/**
 * Home, work and favourite trips, on the device (SharedPreferences + JSON). Exposed as
 * flows so the search screen updates the moment something is saved.
 */
class SavedPlacesRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("xr_saved_places", Context.MODE_PRIVATE)

    private val _home = MutableStateFlow(readPlace(KEY_HOME, PlaceKind.Home))
    val home: StateFlow<Place?> = _home.asStateFlow()

    private val _work = MutableStateFlow(readPlace(KEY_WORK, PlaceKind.Work))
    val work: StateFlow<Place?> = _work.asStateFlow()

    private val _favorites = MutableStateFlow(readFavorites())
    val favorites: StateFlow<List<FavoriteTrip>> = _favorites.asStateFlow()

    fun setHome(place: Place?) {
        writePlace(KEY_HOME, place)
        _home.value = place?.copy(kind = PlaceKind.Home, name = "Maison")
    }

    fun setWork(place: Place?) {
        writePlace(KEY_WORK, place)
        _work.value = place?.copy(kind = PlaceKind.Work, name = "Travail")
    }

    fun toggleFavorite(trip: FavoriteTrip) {
        val current = _favorites.value
        val updated = if (current.any { it.id == trip.id }) {
            current.filter { it.id != trip.id }
        } else {
            (listOf(trip) + current).take(MAX_FAVORITES)
        }
        writeFavorites(updated)
        _favorites.value = updated
    }

    fun isFavorite(id: String): Boolean = _favorites.value.any { it.id == id }

    // ---- storage ---------------------------------------------------------------

    private fun readPlace(key: String, kind: PlaceKind): Place? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching { toPlace(JSONObject(raw), kind) }.getOrNull()
    }

    private fun writePlace(key: String, place: Place?) {
        prefs.edit().apply {
            if (place == null) remove(key) else putString(key, toJson(place).toString())
            apply()
        }
    }

    private fun readFavorites(): List<FavoriteTrip> {
        val raw = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val to = o.optJSONObject("to")?.let { toPlace(it, PlaceKind.Favorite) } ?: return@mapNotNull null
                val from = o.optJSONObject("from")?.let { toPlace(it, PlaceKind.Result) }
                FavoriteTrip(to, from)
            }
        }.getOrDefault(emptyList())
    }

    private fun writeFavorites(list: List<FavoriteTrip>) {
        val array = JSONArray()
        list.forEach { trip ->
            array.put(
                JSONObject().apply {
                    put("to", toJson(trip.to))
                    trip.from?.let { put("from", toJson(it)) }
                },
            )
        }
        prefs.edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    private fun toJson(p: Place) = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("subtitle", p.subtitle)
        put("lat", p.lat)
        put("lon", p.lon)
    }

    private fun toPlace(o: JSONObject, kind: PlaceKind) = Place(
        id = o.optString("id"),
        name = o.optString("name"),
        subtitle = o.optString("subtitle"),
        kind = kind,
        lat = o.optDouble("lat"),
        lon = o.optDouble("lon"),
    )

    private companion object {
        const val KEY_HOME = "home"
        const val KEY_WORK = "work"
        const val KEY_FAVORITES = "favorites"
        const val MAX_FAVORITES = 12
    }
}
