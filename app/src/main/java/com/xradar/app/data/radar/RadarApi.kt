package com.xradar.app.data.radar

import com.xradar.app.BuildConfig
import com.xradar.app.core.model.GeoPoint
import com.xradar.app.core.model.Radar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Thin HTTP client for the x_radar backend. */
class RadarApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Radars within [radiusM] of a point; null when the request failed (not "no radars"). */
    suspend fun near(lat: Double, lon: Double, radiusM: Int): List<Radar>? = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/api/radars/near?lat=$lat&lon=$lon&radius=$radiusM"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            parse(body)
        }
    }

    /**
     * Radars on the trip: within the backend's buffer of the route polyline ([points]),
     * like the road signs. Null when the request failed — e.g. a backend without the
     * endpoint yet (404) — so the caller can fall back to the ring around the driver.
     */
    suspend fun route(points: List<GeoPoint>): List<Radar>? = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext emptyList()
        val coords = JSONArray()
        points.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
        val body = JSONObject().put("coordinates", coords).toString().toRequestBody(JSON)
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/radars/route").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val text = response.body?.string() ?: return@use null
            parse(text)
        }
    }

    private fun parse(json: String): List<Radar> {
        val array = JSONObject(json).optJSONArray("radars") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Radar(
                id = o.optString("id"),
                code = o.optString("type"),
                vma = if (o.isNull("vma")) null else o.optInt("vma"),
                lat = o.optDouble("lat"),
                lon = o.optDouble("lon"),
            )
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
