package com.xradar.app.data.radar

import com.xradar.app.BuildConfig
import com.xradar.app.core.model.Radar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Thin HTTP client for the x_radar backend. */
class RadarApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun near(lat: Double, lon: Double, radiusM: Int): List<Radar> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/api/radars/near?lat=$lat&lon=$lon&radius=$radiusM"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val body = response.body?.string() ?: return@use emptyList()
            parse(body)
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
}
