package com.eona.app.data.trips

import com.eona.app.BuildConfig
import com.eona.app.core.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** The link a driver hands out, as their own app sees it. */
data class TripShare(
    val token: String,
    val url: String,
    val endsAtMs: Long,
    val followers: Int,
    val arrived: Boolean,
)

/**
 * A trip being followed, as the other person's app sees it: where the driver is, where they go,
 * and when they should get there. Nothing else travels.
 */
data class FollowedTrip(
    val name: String,
    val toLabel: String?,
    val destination: GeoPoint?,
    val route: List<GeoPoint>,
    val position: GeoPoint?,
    val bearing: Double?,
    val remainingMeters: Int?,
    val etaAtMs: Long?,
    val arrived: Boolean,
)

/**
 * "Partager mon trajet" (`/api/trips`): the driver opens a link, someone follows the trip live,
 * and everything stops at the arrival. The backend keeps it in memory — no trace is written.
 */
class TripShareApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Opens the link for the trip being driven; null when the backend refuses. */
    suspend fun open(toLabel: String?, destination: GeoPoint?, route: List<GeoPoint>, token: String?): TripShare? {
        val json = JSONObject()
        toLabel?.let { json.put("toLabel", it) }
        destination?.let { json.put("destination", JSONObject().put("lat", it.lat).put("lon", it.lon)) }
        if (route.isNotEmpty()) json.put("route", TripJson.coordinates(route))
        return share("POST", json, token)
    }

    /** Where the driver is now, and what is left of the trip. */
    suspend fun update(
        position: GeoPoint?,
        bearing: Double?,
        remainingMeters: Int?,
        etaSeconds: Int?,
        arrived: Boolean = false,
        token: String?,
    ): TripShare? {
        val json = JSONObject()
        position?.let { json.put("lat", it.lat).put("lon", it.lon) }
        bearing?.let { json.put("bearing", it) }
        remainingMeters?.let { json.put("remainingM", it) }
        etaSeconds?.let { json.put("etaS", it) }
        if (arrived) json.put("arrived", true)
        return share("PATCH", json, token)
    }

    /** Stops sharing: the link stops working at once. */
    suspend fun close(token: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(TripJson.request(baseUrl, "DELETE", "/api/trips/share", null, token)).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** The trip behind a shared link; null once it is over. */
    suspend fun follow(shareToken: String, token: String?): FollowedTrip? = withContext(Dispatchers.IO) {
        val path = "/api/trips/shared/" + URLEncoder.encode(shareToken, "UTF-8")
        runCatching {
            client.newCall(TripJson.request(baseUrl, "GET", path, null, token)).execute().use { r ->
                if (!r.isSuccessful) return@use null
                JSONObject(r.body?.string() ?: "").optJSONObject("share")?.let(::followed)
            }
        }.getOrNull()
    }

    private suspend fun share(method: String, json: JSONObject?, token: String?): TripShare? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(TripJson.request(baseUrl, method, "/api/trips/share", json, token)).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val share = JSONObject(r.body?.string() ?: "").optJSONObject("share") ?: return@use null
                val shareToken = share.optString("token")
                val url = share.optString("url")
                if (shareToken.isBlank() || url.isBlank()) return@use null
                TripShare(
                    token = shareToken,
                    url = url,
                    endsAtMs = TripJson.millis(share, "endsAt") ?: (System.currentTimeMillis() + 3_600_000),
                    followers = share.optInt("followers"),
                    arrived = share.optBoolean("arrived"),
                )
            }
        }.getOrNull()
    }

    private fun followed(share: JSONObject): FollowedTrip {
        val position = share.optJSONObject("position")
        return FollowedTrip(
            name = TripJson.string(share, "name") ?: "Un conducteur",
            toLabel = TripJson.string(share, "toLabel"),
            destination = TripJson.point(share.optJSONObject("destination")),
            route = TripJson.lonLats(share.optJSONArray("route")),
            position = TripJson.point(position),
            bearing = position?.optDouble("bearing")?.takeIf { it.isFinite() },
            remainingMeters = TripJson.int(share, "remainingM"),
            etaAtMs = TripJson.millis(share, "etaAt"),
            arrived = share.optBoolean("arrived"),
        )
    }
}

/** The small readers and writers both trip APIs share. */
internal object TripJson {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun request(baseUrl: String, method: String, path: String, json: JSONObject?, token: String?): Request {
        val body = json?.toString()?.toRequestBody(JSON)
        return Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .method(method, body ?: if (method == "POST" || method == "PATCH") "{}".toRequestBody(JSON) else null)
            .build()
    }

    /** [[lon, lat], …], as the backend takes a route. */
    fun coordinates(points: List<GeoPoint>): JSONArray =
        JSONArray().apply { points.forEach { put(JSONArray().put(it.lon).put(it.lat)) } }

    fun lonLats(array: JSONArray?): List<GeoPoint> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val pair = array.optJSONArray(i) ?: return@mapNotNull null
            val lon = pair.optDouble(0)
            val lat = pair.optDouble(1)
            if (lat.isFinite() && lon.isFinite()) GeoPoint(lat, lon) else null
        }
    }

    fun point(o: JSONObject?): GeoPoint? {
        if (o == null) return null
        val lat = o.optDouble("lat")
        val lon = o.optDouble("lon")
        return if (lat.isFinite() && lon.isFinite()) GeoPoint(lat, lon) else null
    }

    fun string(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.optString(key).ifBlank { null }

    fun int(o: JSONObject, key: String): Int? = if (!o.has(key) || o.isNull(key)) null else o.optInt(key)

    /** An ISO-8601 date (fractional seconds or not), in epoch millis. */
    fun millis(o: JSONObject, key: String): Long? =
        string(o, key)?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
}
