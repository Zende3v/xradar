package com.xradar.app.data.live

import com.xradar.app.BuildConfig
import com.xradar.app.core.model.LiveUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Client for live driver positions (`/api/live`). Auth via Bearer token. */
class LiveApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun share(token: String, lat: Double, lon: Double, bearing: Float?, speedKmh: Int?, visible: Boolean) =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("lat", lat).put("lon", lon).put("visible", visible)
                .apply {
                    if (bearing != null) put("bearing", bearing)
                    if (speedKmh != null) put("speedKmh", speedKmh)
                }
                .toString().toRequestBody(JSON)
            val req = Request.Builder().url(url("/api/live/position"))
                .header("Authorization", "Bearer $token").post(payload).build()
            runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
        }

    suspend fun near(token: String, lat: Double, lon: Double, radiusM: Int): List<LiveUser> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url("/api/live/near?lat=$lat&lon=$lon&radius=$radiusM"))
                .header("Authorization", "Bearer $token").build()
            runCatching {
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use emptyList()
                    val arr = JSONObject(r.body?.string() ?: "").optJSONArray("users") ?: return@use emptyList()
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        LiveUser(
                            id = o.optString("id"),
                            username = o.optString("username").ifBlank { null },
                            lat = o.optDouble("lat"),
                            lon = o.optDouble("lon"),
                            bearingDeg = if (o.isNull("bearing")) null else o.optDouble("bearing").toFloat(),
                            avatarUrl = o.optString("avatarUrl").ifBlank { null },
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

    private fun url(path: String) = "${baseUrl.trimEnd('/')}$path"

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
