package com.eona.app.data.routing

import com.eona.app.BuildConfig
import com.eona.app.core.model.FasterRoute
import com.eona.app.core.model.GeoPoint
import com.eona.app.core.model.Route
import com.eona.app.core.model.RouteStep
import com.eona.app.data.account.AccessDenial
import com.eona.app.data.account.AccessDeniedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Calls the backend's /api/route (which proxies OSRM). */
class RoutingApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** The faster-route check asks ORS and TomTom several times in a row. */
    private val fasterClient = client.newBuilder().readTimeout(40, TimeUnit.SECONDS).build()

    /**
     * [avoid] holds "tolls" and/or "highways"; the backend maps them to ORS features.
     * A restricted account, or a guest past today's trips, is refused: that throws
     * [AccessDeniedException]; null is a route not obtained.
     */
    suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        avoid: List<String> = emptyList(),
    ): Route? = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/api/route" +
            "?from=${from.lat},${from.lon}&to=${to.lat},${to.lon}" +
            if (avoid.isEmpty()) "" else "&avoid=${avoid.joinToString(",")}"
        // The backend refuses routing to a restricted account — it needs to know who asks.
        val request = Request.Builder().url(url).apply {
            com.eona.app.data.account.AccountRepository.token?.let { header("Authorization", "Bearer $it") }
        }.build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            AccessDenial.of(response.code, body)?.let { throw AccessDeniedException(it) }
            if (!response.isSuccessful || body == null) return@use null
            parse(body)
        }
    }

    /**
     * The rest of the route being followed ([remaining], from the driver) against variants
     * around its traffic jams, all timed by TomTom with the traffic (`/api/route/faster`): a
     * route only when the backend finds it saves enough time, or goes around a closed road.
     * [sinceRerouteSeconds], the time since the last switch for traffic, makes it stricter for
     * a while. Null otherwise, or when the check failed.
     */
    suspend fun faster(remaining: List<GeoPoint>, avoid: List<String>, sinceRerouteSeconds: Int?): FasterRoute? = withContext(Dispatchers.IO) {
        if (remaining.size < 2) return@withContext null
        val coords = JSONArray()
        remaining.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
        val body = JSONObject().put("coordinates", coords).put("avoid", JSONArray(avoid))
        if (sinceRerouteSeconds != null) body.put("sinceRerouteS", sinceRerouteSeconds)
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/route/faster")
            .post(body.toString().toRequestBody(JSON))
            .apply { com.eona.app.data.account.AccountRepository.token?.let { header("Authorization", "Bearer $it") } }
            .build()
        runCatching {
            fasterClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val better = JSONObject(response.body?.string() ?: "").optJSONObject("better") ?: return@use null
                val route = better.optJSONObject("route")?.let { parse(it) } ?: return@use null
                val gain = better.optInt("gainS")
                val closed = better.optBoolean("closed")
                if (gain > 0 || closed) FasterRoute(route, gain, closed) else null
            }
        }.getOrNull()
    }

    private fun parse(json: String): Route? = parse(JSONObject(json))

    private fun parse(obj: JSONObject): Route? {
        val coords = obj.optJSONArray("coordinates") ?: return null
        val points = (0 until coords.length()).mapNotNull { i ->
            val pair = coords.optJSONArray(i) ?: return@mapNotNull null
            // Backend sends [lon, lat].
            GeoPoint(lat = pair.getDouble(1), lon = pair.getDouble(0))
        }
        if (points.size < 2) return null
        return Route(points, obj.optInt("distanceM"), obj.optInt("durationS"), parseSteps(obj))
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }

    /** Turn-by-turn steps; empty if the backend hasn't been redeployed with steps=true. */
    private fun parseSteps(obj: JSONObject): List<RouteStep> {
        val arr = obj.optJSONArray("steps") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val loc = s.optJSONArray("location") ?: return@mapNotNull null
            RouteStep(
                // Backend sends [lon, lat].
                location = GeoPoint(lat = loc.getDouble(1), lon = loc.getDouble(0)),
                type = s.optString("type", ""),
                modifier = if (s.isNull("modifier")) null else s.optString("modifier").ifBlank { null },
                name = s.optString("name", ""),
                distanceMeters = s.optInt("distanceM"),
                exit = if (s.isNull("exit")) null else s.optInt("exit"),
            )
        }
    }
}
