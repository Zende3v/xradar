package com.xradar.app.data.reports

import com.xradar.app.BuildConfig
import com.xradar.app.core.model.RadarZone
import com.xradar.app.core.model.ReportType
import com.xradar.app.core.model.UserReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Nearby crowdsourced data: point reports + aggregated radar-car zones. */
data class NearReports(
    val reports: List<UserReport> = emptyList(),
    val zones: List<RadarZone> = emptyList(),
)

/** A new report to post, with the extra fields some types require. */
data class NewReport(
    val type: ReportType,
    val lat: Double,
    val lon: Double,
    val plate: String? = null,
    val street: String? = null,
    val side: String? = null,
    /** "same" (my carriageway) or "opposite". */
    val direction: String = "same",
    /** The driver's course when reporting — orients the control zone. */
    val bearingDeg: Double? = null,
)

/** HTTP client for crowdsourced reports (`/api/reports`). */
class ReportsApi(private val baseUrl: String = BuildConfig.BACKEND_BASE_URL) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun near(lat: Double, lon: Double, radiusM: Int): NearReports = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/api/reports/near?lat=$lat&lon=$lon&radius=$radiusM"
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use NearReports()
            val body = response.body?.string() ?: return@use NearReports()
            val root = JSONObject(body)
            val reports = root.optJSONArray("reports")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::toReport) }
            } ?: emptyList()
            val zones = root.optJSONArray("zones")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::toZone) }
            } ?: emptyList()
            NearReports(reports, zones)
        }
    }

    suspend fun create(report: NewReport, token: String?, deviceId: String?): UserReport? = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("type", report.type.wire)
            .put("lat", report.lat)
            .put("lon", report.lon)
            .apply {
                if (deviceId != null) put("deviceId", deviceId)
                if (report.plate != null) put("plate", report.plate)
                if (report.street != null) put("street", report.street)
                if (report.side != null) put("side", report.side)
                put("direction", report.direction)
                if (report.bearingDeg != null) put("bearing", report.bearingDeg)
            }
            .toString()
            .toRequestBody(JSON)
        val builder = Request.Builder().url("${baseUrl.trimEnd('/')}/api/reports").post(payload)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val request = builder.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            JSONObject(body).optJSONObject("report")?.let(::toReport)
        }
    }

    suspend fun delete(id: String, token: String?): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${baseUrl.trimEnd('/')}/api/reports/$id")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .delete().build()
        client.newCall(req).execute().use { it.isSuccessful }
    }

    suspend fun vote(id: String, confirm: Boolean): Boolean = withContext(Dispatchers.IO) {
        val action = if (confirm) "confirm" else "deny"
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/reports/$id/$action")
            .post("".toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { it.isSuccessful }
    }

    private fun toReport(o: JSONObject): UserReport? {
        val type = ReportType.fromWire(o.optString("type")) ?: return null
        val createdAt = o.optLong("createdAt")
        return UserReport(
            id = o.optString("id"),
            type = type,
            lat = o.optDouble("lat"),
            lon = o.optDouble("lon"),
            ageMillis = (System.currentTimeMillis() - createdAt).coerceAtLeast(0),
            confirmations = o.optInt("confirmations"),
            contradictions = o.optInt("contradictions"),
            reporters = o.optInt("reporters", 1).coerceAtLeast(1),
            direction = o.optString("direction").ifBlank { "same" },
            bearingDeg = if (o.isNull("bearing")) null else o.optDouble("bearing"),
            score = o.optInt("score"),
            impactMeters = o.optDouble("impactM", 1500.0),
            persistent = o.optBoolean("persistent"),
            reporterRole = o.optString("reporterRole").ifBlank { "guest" },
            street = if (o.isNull("street")) null else o.optString("street").ifBlank { null },
            side = if (o.isNull("side")) null else o.optString("side").ifBlank { null },
        )
    }

    private fun toZone(o: JSONObject): RadarZone? {
        val id = o.optString("id").ifBlank { return null }
        return RadarZone(
            id = id,
            lat = o.optDouble("lat"),
            lon = o.optDouble("lon"),
            radiusMeters = o.optDouble("radiusM"),
            count = o.optInt("count"),
        )
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
