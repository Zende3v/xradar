package com.eona.app.data.traffic

import com.eona.app.BuildConfig
import com.eona.app.core.drive.Slowdown
import com.eona.app.core.model.GeoPoint
import com.eona.app.core.model.TrafficAnswer
import com.eona.app.core.model.TrafficLevel
import com.eona.app.core.model.TrafficStretch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Traffic on the route being followed (`/api/traffic/route`: TomTom's and the drivers' jams, the
 * TomTom key staying on the server) and the slowdown probes ("Partager les ralentissements").
 */
class TrafficApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * The traffic on [points] (the rest of the route), each source whole (raw, data.gouv
     * included): TomTom only with [tomtom] and within the backend's budget. Null when the backend
     * could not say (offline): the caller keeps what it shows. An empty answer is a clear road.
     * [aheadMeters], the driver's metres along [points], lets the backend say whether a faster
     * route is worth looking for.
     */
    suspend fun route(points: List<GeoPoint>, aheadMeters: Double?, token: String?, tomtom: Boolean = true): TrafficAnswer? = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext null
        val coords = JSONArray()
        points.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
        val body = JSONObject()
            .put("coordinates", coords)
            .put("tomtom", tomtom)
            .put("raw", true)
            .put("sources", JSONArray().put(TrafficStretch.DATAGOUV))
        if (aheadMeters != null) body.put("aheadM", Math.round(aheadMeters))
        runCatching {
            client.newCall(post("/api/traffic/route", body, token)).execute().use { r ->
                if (r.isSuccessful) parse(r.body?.string()) else null
            }
        }.getOrNull()
    }

    /**
     * A slowdown the app measured, sent without the account being kept with it. True when the
     * jam is already known there (the driver is asked nothing), false when not; null when the
     * backend refused it or could not say.
     */
    suspend fun probe(slowdown: Slowdown, token: String?): Boolean? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("lat", slowdown.lat)
            .put("lon", slowdown.lon)
            .put("bearing", slowdown.bearingDeg)
            .put("speedKmh", slowdown.speedKmh)
            .put("limitKmh", slowdown.limitKmh)
        runCatching {
            client.newCall(post("/api/traffic/probe", body, token)).execute().use { r ->
                if (r.isSuccessful) JSONObject(r.body?.string() ?: "").optBoolean("known") else null
            }
        }.getOrNull()
    }

    /** "Non" to "Ralentissement du trafic ?": the driver's recent probes are taken back. */
    suspend fun dismissProbe(token: String?) {
        withContext(Dispatchers.IO) {
            runCatching { client.newCall(post("/api/traffic/probe/dismiss", JSONObject(), token)).execute().close() }
        }
    }

    private fun post(path: String, body: JSONObject, token: String?): Request =
        Request.Builder()
            .url("${baseUrl.trimEnd('/')}$path")
            .post(body.toString().toRequestBody(JSON))
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()

    private fun parse(json: String?): TrafficAnswer? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        val sections = o.optJSONArray("sections") ?: JSONArray()
        val stretches = (0 until sections.length()).mapNotNull { i ->
            val s = sections.optJSONObject(i) ?: return@mapNotNull null
            val level = TrafficLevel.fromWire(s.optString("level")) ?: return@mapNotNull null
            val from = s.optDouble("fromM")
            val to = s.optDouble("toM")
            if (!(to > from)) return@mapNotNull null
            TrafficStretch(
                from,
                to,
                level,
                if (s.isNull("delayS")) null else s.optInt("delayS"),
                source = if (s.isNull("source")) TrafficStretch.TOMTOM else s.optString("source").ifBlank { TrafficStretch.TOMTOM },
                kind = if (s.isNull("kind")) null else s.optString("kind").ifBlank { null },
            )
        }
        // An older backend answers without "tomtom": it asked TomTom whenever it had a key.
        val tomtom = if (o.has("tomtom")) o.optBoolean("tomtom") else !o.isNull("travelS")
        return TrafficAnswer(
            totalMeters = o.optDouble("totalM", 0.0),
            stretches = stretches,
            travelSeconds = if (o.isNull("travelS")) null else o.optInt("travelS").takeIf { it > 0 },
            tomtom = tomtom,
            datagouvShown = o.optBoolean("datagouv", false),
            minGapSeconds = o.optInt("minGapS", 0),
            worthChecking = o.optBoolean("check"),
        )
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
