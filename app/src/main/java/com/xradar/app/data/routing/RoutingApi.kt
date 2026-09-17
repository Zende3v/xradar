package com.xradar.app.data.routing

import com.xradar.app.BuildConfig
import com.xradar.app.core.model.GeoPoint
import com.xradar.app.core.model.Route
import com.xradar.app.core.model.RouteStep
import com.xradar.app.data.account.AccessDenial
import com.xradar.app.data.account.AccessDeniedException
import com.xradar.app.data.network.FallbackDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Calls the backend's /api/route (which proxies OSRM). */
class RoutingApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .dns(FallbackDns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * [avoid] holds "tolls", "highways" and/or "traffic"; the backend maps them to ORS features.
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
            com.xradar.app.data.account.AccountRepository.token?.let { header("Authorization", "Bearer $it") }
        }.build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            AccessDenial.of(response.code, body)?.let { throw AccessDeniedException(it) }
            if (!response.isSuccessful || body == null) return@use null
            parse(body)
        }
    }

    private fun parse(json: String): Route? {
        val obj = JSONObject(json)
        val coords = obj.optJSONArray("coordinates") ?: return null
        val points = (0 until coords.length()).mapNotNull { i ->
            val pair = coords.optJSONArray(i) ?: return@mapNotNull null
            // Backend sends [lon, lat].
            GeoPoint(lat = pair.getDouble(1), lon = pair.getDouble(0))
        }
        if (points.size < 2) return null
        return Route(points, obj.optInt("distanceM"), obj.optInt("durationS"), parseSteps(obj))
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
