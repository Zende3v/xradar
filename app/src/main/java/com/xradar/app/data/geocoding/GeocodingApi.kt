package com.xradar.app.data.geocoding

import com.xradar.app.core.model.Place
import com.xradar.app.core.model.PlaceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Free French address geocoder — Base Adresse Nationale (api-adresse.data.gouv.fr),
 * official, no key, no self-hosting. Turns typed text into real places + coords.
 */
class GeocodingApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * [aroundLat]/[aroundLon] bias the results toward the driver, so "rue de la gare"
     * returns the one next to them and not the other end of France.
     */
    suspend fun search(
        query: String,
        limit: Int = 8,
        aroundLat: Double? = null,
        aroundLon: Double? = null,
    ): List<Place> = withContext(Dispatchers.IO) {
        val near = if (aroundLat != null && aroundLon != null) "&lat=$aroundLat&lon=$aroundLon" else ""
        val url = "https://api-adresse.data.gouv.fr/search/?q=${URLEncoder.encode(query, "UTF-8")}&limit=$limit$near"
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            parse(response.body?.string() ?: return@use emptyList())
        }
    }

    private fun parse(json: String): List<Place> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val feature = features.optJSONObject(i) ?: return@mapNotNull null
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val props = feature.optJSONObject("properties") ?: JSONObject()
            val label = props.optString("label").ifEmpty { props.optString("name") }
            if (label.isEmpty()) return@mapNotNull null
            Place(
                id = props.optString("id").ifEmpty { "${coords.getDouble(1)},${coords.getDouble(0)}" },
                name = label,
                subtitle = props.optString("context"),
                kind = PlaceKind.Result,
                lat = coords.getDouble(1),
                lon = coords.getDouble(0),
            )
        }
    }
}
