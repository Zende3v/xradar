package com.xradar.app.data.speedlimits

import com.xradar.app.BuildConfig
import com.xradar.app.core.model.SpeedLimitChange
import com.xradar.app.core.model.SpeedLimitSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A limit proposal to post: the limit the sign shows, where, and what the HUD showed. */
data class NewSpeedLimitReport(
    val lat: Double,
    val lon: Double,
    /** The driver's course: a sign only applies to the way it faces. */
    val bearingDeg: Double?,
    val displayedKmh: Int?,
    val displayedSource: SpeedLimitSource?,
    val newKmh: Int,
)

/** HTTP client for the speed-limit maintenance (`/api/speed-limits`). */
class SpeedLimitApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Where the proposal stands; null when refused, or when it confirmed a limit nobody questioned. */
    suspend fun report(report: NewSpeedLimitReport, token: String?, deviceId: String?): SpeedLimitChange? =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("lat", report.lat)
                .put("lon", report.lon)
                .put("newKmh", report.newKmh)
                .apply {
                    if (report.bearingDeg != null) put("bearing", report.bearingDeg)
                    if (report.displayedKmh != null) put("displayedKmh", report.displayedKmh)
                    if (report.displayedSource != null) put("displayedSource", report.displayedSource.wire)
                    if (deviceId != null) put("deviceId", deviceId)
                }
                .toString()
                .toRequestBody(JSON)
            val builder = Request.Builder().url("${baseUrl.trimEnd('/')}/api/speed-limits/reports").post(payload)
            if (token != null) builder.header("Authorization", "Bearer $token")
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                JSONObject(body).optJSONObject("change")?.let(::toChange)
            }
        }

    private fun toChange(o: JSONObject) = SpeedLimitChange(
        id = o.optString("id"),
        status = SpeedLimitChange.Status.fromWire(o.optString("status")),
        oldKmh = if (o.isNull("oldKmh")) null else o.optInt("oldKmh"),
        newKmh = if (o.isNull("newKmh")) null else o.optInt("newKmh"),
        reporters = o.optInt("reporters"),
        required = o.optInt("required"),
    )

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
