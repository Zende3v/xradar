package com.xradar.app.data.places

import com.xradar.app.BuildConfig
import com.xradar.app.core.model.ChargingInfo
import com.xradar.app.core.model.FuelPrice
import com.xradar.app.core.model.FuelType
import com.xradar.app.core.model.NearbyInfo
import com.xradar.app.core.model.OpenState
import com.xradar.app.core.model.OpeningHours
import com.xradar.app.core.model.ParkingInfo
import com.xradar.app.core.model.ParkingType
import com.xradar.app.core.model.Place
import com.xradar.app.core.model.PlaceCategory
import com.xradar.app.core.model.PlaceKind
import com.xradar.app.core.model.StationFuel
import com.xradar.app.core.model.TimeSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Nearest fuel stations, chargers, parkings… from the backend (`/api/places`, PostGIS). */
class PlacesApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // The backend answers from its own database in milliseconds.
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * The pool of nearest places of [category] (up to 60), for [com.xradar.app.core.model.NearbyPicker]
     * to rank; null when the search failed (no network, backend down).
     */
    suspend fun near(category: PlaceCategory, lat: Double, lon: Double): List<Place>? =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl.trimEnd('/')}/api/places/near" +
                "?lat=$lat&lon=$lon&kind=${category.wire}&pool=1"
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    parse(r.body?.string(), category)
                }
            }.getOrNull()
        }

    private fun parse(body: String?, category: PlaceCategory): List<Place> {
        val arr = JSONObject(body ?: "").optJSONArray("places") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.stringOrNull("name") ?: return@mapNotNull null
            Place(
                id = o.stringOrNull("id") ?: "${o.optDouble("lat")},${o.optDouble("lon")}",
                name = name,
                subtitle = o.stringOrNull("subtitle") ?: "",
                kind = PlaceKind.Result,
                lat = o.optDouble("lat"),
                lon = o.optDouble("lon"),
                // Official prices exist for fuel stations only; nothing else reads this field.
                fuel = if (category == PlaceCategory.Fuel) o.optJSONObject("fuel")?.let(::parseFuel) else null,
                distanceMeters = if (o.has("distanceM")) o.optInt("distanceM") else null,
                nearby = NearbyInfo(
                    brand = o.stringOrNull("brand"),
                    hours = o.optJSONObject("hours")?.let(::parseHours),
                    customersOnly = o.optBoolean("customersOnly"),
                    charging = o.optJSONObject("charging")?.let(::parseCharging),
                    parking = o.optJSONObject("parking")?.let(::parseParking),
                    stars = if (o.isNull("stars")) null else o.optInt("stars").takeIf { it in 1..5 },
                ),
            )
        }
    }

    /** `{state, alwaysOpen, today: [{from, to}], nextAt, source}` → [OpeningHours]. */
    private fun parseHours(o: JSONObject): OpeningHours {
        val today = o.optJSONArray("today")
        return OpeningHours(
            state = when (o.optString("state")) {
                "open" -> OpenState.Open
                "closed" -> OpenState.Closed
                else -> OpenState.Unknown
            },
            alwaysOpen = o.optBoolean("alwaysOpen"),
            today = (0 until (today?.length() ?: 0)).mapNotNull { i ->
                val slot = today?.optJSONObject(i) ?: return@mapNotNull null
                TimeSlot(slot.stringOrNull("from") ?: return@mapNotNull null, slot.stringOrNull("to") ?: return@mapNotNull null)
            },
            nextChangeMillis = if (o.isNull("nextAt")) null else o.optLong("nextAt"),
            official = o.optString("source") == "official",
        )
    }

    /** `{maxKw, connectors: [...], points}` → [ChargingInfo]. */
    private fun parseCharging(o: JSONObject): ChargingInfo {
        val connectors = o.optJSONArray("connectors")
        return ChargingInfo(
            maxKw = if (o.isNull("maxKw")) null else o.optDouble("maxKw").takeIf { it.isFinite() && it > 0.0 },
            connectors = (0 until (connectors?.length() ?: 0)).mapNotNull { connectors?.optString(it)?.ifBlank { null } },
            points = if (o.isNull("points")) null else o.optInt("points").takeIf { it > 0 },
        )
    }

    /** `{fee, type, capacity, parkAndRide}` → [ParkingInfo]. */
    private fun parseParking(o: JSONObject): ParkingInfo = ParkingInfo(
        fee = if (o.isNull("fee")) null else o.optBoolean("fee"),
        type = when (o.stringOrNull("type")) {
            "underground" -> ParkingType.Underground
            "multi_storey" -> ParkingType.MultiStorey
            "rooftop" -> ParkingType.Rooftop
            "surface" -> ParkingType.Surface
            "street_side" -> ParkingType.StreetSide
            else -> null
        },
        capacity = if (o.isNull("capacity")) null else o.optInt("capacity").takeIf { it > 0 },
        parkAndRide = o.optBoolean("parkAndRide"),
    )

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

    /** A string field, null when missing, JSON null or blank (optString would say "null"). */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }
}
