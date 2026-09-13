package com.xradar.app.data.speedlimits

import com.xradar.app.core.model.SpeedLimitChange

/**
 * Speed-limit proposals. Swallows network failures to a safe default, like the reports,
 * so the HUD never breaks.
 */
class SpeedLimitRepository(private val api: SpeedLimitApi = SpeedLimitApi()) {

    suspend fun report(report: NewSpeedLimitReport, token: String?, deviceId: String?): SpeedLimitChange? =
        runCatching { api.report(report, token, deviceId) }.getOrNull()
}
