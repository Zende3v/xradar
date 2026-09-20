package com.eona.app.data.account

import org.json.JSONObject

/**
 * The backend refused an action for the account's access: the trial or the subscription is
 * over, or a guest used up today's reports or trips.
 */
enum class AccessDenial {
    SubscriptionRequired,
    DailyReportLimit,
    DailyTripLimit;

    companion object {
        /** The refusal an answer ([code], [body]) carries, if it is one. */
        fun of(code: Int, body: String?): AccessDenial? {
            if (code != 403 && code != 429) return null
            return when (runCatching { JSONObject(body ?: "").optString("error") }.getOrNull()) {
                "subscription required" -> SubscriptionRequired
                "daily report limit" -> DailyReportLimit
                "daily trip limit" -> DailyTripLimit
                else -> null
            }
        }
    }
}

/** Thrown by the API clients when the backend refuses an action for the account's access. */
class AccessDeniedException(val denial: AccessDenial) : Exception("access denied: $denial")
