package com.xradar.app.data.places

import com.xradar.app.BuildConfig
import com.xradar.app.core.model.FuelPrice
import com.xradar.app.core.model.FuelType
import com.xradar.app.core.model.Place
import com.xradar.app.core.model.PlaceCategory
import com.xradar.app.core.model.PlaceKind
import com.xradar.app.core.model.StationFuel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Nearest fuel stations, chargers, parkings… from the backend (`/api/places`). */
class PlacesApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // Overpass can be slow when the search has to widen; give it room.
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun near(category: PlaceCategory, lat: Double, lon: Double): List<Place> =
        withContext(Dispatchers.IO) {
            // Fuel asks for the bigger pool, so the stations that show a price can come first.
            val pool = if (category == PlaceCategory.Fuel) "&pool=1" else ""
            val url = "${baseUrl.trimEnd('/')}/api/places/near" +
                "?lat=$lat&lon=$lon&kind=${category.wire}$pool"
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use emptyList()
                    parse(r.body?.string(), category)
                }
            }.getOrDefault(emptyList())
        }

    private fun parse(body: String?, category: PlaceCategory): List<Place> {
        val arr = JSONObject(body ?: "").optJSONArray("places") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").ifBlank { return@mapNotNull null }
            val distance = o.optInt("distanceM")
            val detail = o.optString("subtitle").ifBlank { null }
            Place(
                id = o.optString("id").ifBlank { "${o.optDouble("lat")},${o.optDouble("lon")}" },
                name = name,
                subtitle = listOfNotNull(distanceLabel(distance), detail).joinToString(" · "),
                kind = PlaceKind.Result,
                lat = o.optDouble("lat"),
                lon = o.optDouble("lon"),
                // Official prices exist for fuel stations only; nothing else reads this field.
                fuel = if (category == PlaceCategory.Fuel) o.optJSONObject("fuel")?.let(::parseFuel) else null,
                distanceMeters = if (o.has("distanceM")) distance else null,
            )
        }
    }

    /** `{stationId, matchedBy, prices: [{fuel, price, updatedAt, outOfStock}]}` → [StationFuel]. */
    private fun parseFuel(o: JSONObject): StationFuel? {
        val stationId = o.optString("stationId").ifBlank { return null }
        val arr = o.optJSONArray("prices")
        val prices = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val p = arr?.optJSONObject(i) ?: return@mapNotNull null
            val type = FuelType.fromWire(p.optString("fuel")) ?: return@mapNotNull null
            val euros = p.optDouble("price").takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
            FuelPrice(
                type = type,
                euros = euros,
                updatedAt = if (p.isNull("updatedAt")) null else p.optString("updatedAt").ifBlank { null },
                outOfStock = p.optBoolean("outOfStock"),
            )
        }
        return StationFuel(stationId = stationId, matchedBy = o.optString("matchedBy"), prices = prices)
    }

    /** "850 m" / "12,4 km" — the driver reads a distance, not a number of metres. */
    private fun distanceLabel(meters: Int): String = when {
        meters < 1000 -> "$meters m"
        meters < 10_000 -> "%.1f km".format(meters / 1000.0).replace('.', ',')
        else -> "${(meters / 1000.0).roundToInt()} km"
    }
}
