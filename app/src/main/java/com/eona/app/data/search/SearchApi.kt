package com.eona.app.data.search

import com.eona.app.BuildConfig
import com.eona.app.core.model.Place
import com.eona.app.core.model.PlaceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * What a driver types (`/api/search`): the backend asks a place search and the official address
 * search at once and merges them, so "Lycée Adolphe Chérioux vitry" finds the school and
 * "10 rue de la paix" finds the door. Biased toward where the driver is.
 */
class SearchApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** The places matching [query], nearest to [aroundLat]/[aroundLon] first; empty on failure. */
    suspend fun search(
        query: String,
        aroundLat: Double? = null,
        aroundLon: Double? = null,
        token: String? = null,
        limit: Int = 8,
    ): List<Place> = withContext(Dispatchers.IO) {
        val near = if (aroundLat != null && aroundLon != null) "&lat=$aroundLat&lon=$aroundLon" else ""
        val url = "${baseUrl.trimEnd('/')}/api/search?q=${URLEncoder.encode(query, "UTF-8")}&limit=$limit$near"
        val request = Request.Builder().url(url).apply {
            if (token != null) header("Authorization", "Bearer $token")
        }.build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                parse(response.body?.string())
            }
        }.getOrDefault(emptyList())
    }

    private fun parse(body: String?): List<Place> {
        val results = JSONObject(body ?: return emptyList()).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optDouble("lat")
            val lon = o.optDouble("lon")
            val name = if (o.isNull("name")) null else o.optString("name").ifBlank { null }
            if (!lat.isFinite() || !lon.isFinite() || name == null) return@mapNotNull null
            Place(
                id = if (o.isNull("id")) "$lat,$lon" else o.optString("id").ifBlank { "$lat,$lon" },
                name = name,
                subtitle = if (o.isNull("subtitle")) "" else o.optString("subtitle"),
                kind = PlaceKind.Result,
                lat = lat,
                lon = lon,
                distanceMeters = if (o.has("distanceM") && !o.isNull("distanceM")) o.optInt("distanceM") else null,
            )
        }
    }
}
