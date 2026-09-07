package com.xradar.app.core.model

/** Where the current speed sits relative to the active limit. */
enum class SpeedStatus {
    Safe,
    Caution,
    Over;

    companion object {
        /** Returns null when no limit is known — the UI then uses a neutral color. */
        fun of(speedKmh: Int, limitKmh: Int?): SpeedStatus? {
            if (limitKmh == null) return null
            return when {
                speedKmh > limitKmh -> Over
                speedKmh >= limitKmh - 3 -> Caution
                else -> Safe
            }
        }
    }
}
