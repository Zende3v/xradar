package com.xradar.app.data.signs

import com.xradar.app.BuildConfig
import com.xradar.app.core.model.GeoPoint
import com.xradar.app.core.model.RoadSign
import com.xradar.app.core.model.SignType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The limit on the road under the driver ([kmh] null = not mapped), and that road's id. */
data class RoadLimit(val kmh: Int?, val wayId: String?)

/** Client for OSM road signs (`/api/signs`). */
class SignApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun near(lat: Double, lon: Double, radiusM: Int): List<RoadSign> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/api/signs/near?lat=$lat&lon=$lon&radius=$radiusM"
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { r -> parse(r.body?.string()) }
        }.getOrDefault(emptyList())
    }

    /** All signs along the whole route ([points] as the polyline); null when the request failed. */
    suspend fun route(points: List<GeoPoint>): List<RoadSign>? = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext emptyList()
        val coords = JSONArray()
        points.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
        val body = JSONObject().put("coordinates", coords).toString().toRequestBody(JSON)
        runCatching {
            client.newCall(Request.Builder().url(url("/api/signs/route")).post(body).build())
                .execute().use { r -> if (r.isSuccessful) parse(r.body?.string()) else null }
        }.getOrNull()
    }

    /**
     * Speed limit where the driver is, for the way they go: the backend picks the road by
     * distance, [bearingDeg] (their course) and continuity with [previousWayId] (the road the
     * previous answer gave). Null when the request failed.
     */
    suspend fun limit(
        lat: Double,
        lon: Double,
        bearingDeg: Double? = null,
        previousWayId: String? = null,
    ): RoadLimit? = withContext(Dispatchers.IO) {
        runCatching {
            val u = url(
                "/api/signs/limit?lat=$lat&lon=$lon" +
                    (bearingDeg?.let { "&bearing=$it" } ?: "") +
                    (previousWayId?.let { "&way=$it" } ?: ""),
            )
            client.newCall(Request.Builder().url(u).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = JSONObject(r.body?.string() ?: "")
                RoadLimit(
                    kmh = if (o.isNull("v")) null else o.optInt("v").takeIf { it in 5..130 },
                    wayId = if (o.isNull("way")) null else o.optString("way").ifBlank { null },
                )
            }
        }.getOrNull()
    }

    private fun url(path: String) = "${baseUrl.trimEnd('/')}$path"

    private fun parse(body: String?): List<RoadSign> {
        val arr = JSONObject(body ?: "").optJSONArray("signs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val type = SignType.fromWire(o.optString("type")) ?: return@mapNotNull null
            RoadSign(type, o.optDouble("lat"), o.optDouble("lon"), if (o.has("v")) o.optInt("v") else null)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
