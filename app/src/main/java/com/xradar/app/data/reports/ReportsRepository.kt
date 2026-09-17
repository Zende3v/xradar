package com.xradar.app.data.reports

import com.xradar.app.core.model.UserReport

/**
 * Crowdsourced report access. Swallows network failures to safe defaults so the
 * HUD never breaks.
 */
class ReportsRepository(private val api: ReportsApi = ReportsApi()) {

    /** Null when the reports could not be loaded (keep the ones shown). */
    suspend fun near(lat: Double, lon: Double, radiusM: Int): NearReports? =
        runCatching { api.near(lat, lon, radiusM) }.getOrNull()

    suspend fun create(report: NewReport, token: String?, deviceId: String?): UserReport? =
        runCatching { api.create(report, token, deviceId) }.getOrNull()

    suspend fun delete(id: String, token: String?): Boolean =
        runCatching { api.delete(id, token) }.getOrDefault(false)

    suspend fun vote(id: String, confirm: Boolean, token: String?, deviceId: String?): Boolean =
        runCatching { api.vote(id, confirm, token, deviceId) }.getOrDefault(false)
}
