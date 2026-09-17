package com.xradar.app.data.reports

import com.xradar.app.core.model.UserReport
import com.xradar.app.data.account.AccessDeniedException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Crowdsourced report access. Swallows network failures to safe defaults so the
 * HUD never breaks.
 */
class ReportsRepository(private val api: ReportsApi = ReportsApi()) {

    /** Null when the reports could not be loaded (keep the ones shown). */
    suspend fun near(lat: Double, lon: Double, radiusM: Int): NearReports? =
        runCatching { api.near(lat, lon, radiusM) }.getOrNull()

    /** Null when refused or failed; a refusal for the account's access throws [AccessDeniedException]. */
    suspend fun create(report: NewReport, token: String?, deviceId: String?): UserReport? = try {
        api.create(report, token, deviceId)
    } catch (e: AccessDeniedException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun delete(id: String, token: String?): Boolean =
        runCatching { api.delete(id, token) }.getOrDefault(false)

    suspend fun vote(id: String, confirm: Boolean, token: String?, deviceId: String?): Boolean =
        runCatching { api.vote(id, confirm, token, deviceId) }.getOrDefault(false)
}
