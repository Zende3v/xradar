package com.eona.app.data.radar

import com.eona.app.BuildConfig
import com.eona.app.core.model.GeoPoint
import com.eona.app.core.model.Radar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Thin HTTP client for the EONA backend. */
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

    /**
     * "Pas dans mon sens": radar [id] does not control the way the driver goes ([course]).
     * The radar's quiet course now (null while the votes are too few); a failed [Result] when
     * the request failed.
     */
    suspend fun notMyWay(id: String, course: Double, token: String): Result<Double?> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("course", Math.round(course) % 360).toString().toRequestBody(JSON)
            val url = "${baseUrl.trimEnd('/')}/api/radars/${java.net.URLEncoder.encode(id, "UTF-8")}/not-my-way"
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token").post(body).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val o = JSONObject(response.body?.string() ?: "{}")
                if (o.isNull("quietCourse")) null else o.optDouble("quietCourse").takeIf { it.isFinite() }
            }
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
                // The way it controls: absent from an older backend.
                course = if (o.isNull("course")) null else o.optDouble("course").takeIf { it.isFinite() },
                quietCourse = if (o.isNull("quietCourse")) null else o.optDouble("quietCourse").takeIf { it.isFinite() },
            )
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
