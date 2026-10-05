package com.eona.app.data.parking

import android.content.Context
import android.content.SharedPreferences
import com.eona.app.core.model.ParkedVehicle
import com.eona.app.core.model.ParkingSpot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * « Stationnement » : plusieurs repères, gardés sur ce téléphone, jamais envoyés (iOS
 * ParkingStore). Plus récent d'abord ; au-delà de [LIMIT], le plus ancien part.
 */
object ParkingRepository {
    /** Repères au plus. */
    const val LIMIT = 10
    private const val KEY = "spots"

    private var prefs: SharedPreferences? = null
    private val _spots = MutableStateFlow<List<ParkingSpot>>(emptyList())
    val spots: StateFlow<List<ParkingSpot>> = _spots.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("xr_parking", Context.MODE_PRIVATE)
        prefs = p
        _spots.value = read(p.getString(KEY, null))
    }

    fun spot(id: String): ParkingSpot? = _spots.value.firstOrNull { it.id == id }

    /** Repère posé ici, maintenant, en tête de liste. */
    fun park(lat: Double, lon: Double, vehicle: ParkedVehicle, atMillis: Long = System.currentTimeMillis()): ParkingSpot {
        val spot = ParkingSpot(lat = lat, lon = lon, parkedAtMillis = atMillis, vehicle = vehicle)
        save((listOf(spot) + _spots.value).take(LIMIT))
        return spot
    }

    fun setVehicle(vehicle: ParkedVehicle, id: String) {
        save(_spots.value.map { if (it.id == id) it.copy(vehicle = vehicle) else it })
    }

    fun remove(id: String) {
        save(_spots.value.filter { it.id != id })
    }

    private fun save(value: List<ParkingSpot>) {
        _spots.value = value
        val json = JSONArray()
        value.forEach {
            json.put(
                JSONObject()
                    .put("id", it.id).put("lat", it.lat).put("lon", it.lon)
                    .put("at", it.parkedAtMillis / 1000.0).put("vehicle", it.vehicle.wire),
            )
        }
        prefs?.edit()?.putString(KEY, json.toString())?.apply()
    }

    /** Liste gardée ; entrée illisible : écartée. */
    private fun read(stored: String?): List<ParkingSpot> {
        if (stored.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            val at = o.optDouble("at", Double.NaN)
            if (lat.isNaN() || lon.isNaN() || at.isNaN() || abs(lat) > 90 || abs(lon) > 180) return@mapNotNull null
            ParkingSpot(
                id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                lat = lat,
                lon = lon,
                parkedAtMillis = (at * 1000).toLong(),
                vehicle = ParkedVehicle.fromWire(o.optString("vehicle")),
            )
        }
    }
}
