package com.xradar.app.core.model

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** A guest's daily limits and what they used, as the backend counts them: per day, Paris time. */
data class DailyLimits(
    /** The Paris day the counts are for, "yyyy-MM-dd". */
    val day: String,
    val reportsPerDay: Int,
    val reportsToday: Int,
    val tripsPerDay: Int,
    val tripsToday: Int,
) {
    /** Reports posted on [now]'s day: none when the counts are from another day. */
    fun reportsUsed(now: Instant = Instant.now()): Int = if (day == parisDay(now)) reportsToday else 0

    fun tripsUsed(now: Instant = Instant.now()): Int = if (day == parisDay(now)) tripsToday else 0

    fun reportsLeft(now: Instant = Instant.now()): Int = (reportsPerDay - reportsUsed(now)).coerceAtLeast(0)

    fun tripsLeft(now: Instant = Instant.now()): Int = (tripsPerDay - tripsUsed(now)).coerceAtLeast(0)

    companion object {
        private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

        /** The day of [now] in France, "yyyy-MM-dd", as the backend dates its counts. */
        fun parisDay(now: Instant): String = now.atZone(PARIS).toLocalDate().toString()
    }
}

/** A membership plan. Prices only: there is no payment in the app yet. */
data class SubscriptionPlan(
    val id: String,
    val title: String,
    val priceCents: Int,
    val months: Int,
) {
    /** "12,99 €" */
    val priceLabel: String get() = euros(priceCents)

    /** "/mois", "/an" */
    val periodLabel: String
        get() = when (months) {
            1 -> "/mois"
            12 -> "/an"
            else -> "/$months mois"
        }

    /** A longer plan's cost per month, "soit 11,99 €/mois"; null for the monthly plan. */
    val perMonthLabel: String?
        get() = if (months > 1) "soit ${euros((priceCents.toDouble() / months).roundToInt())}/mois" else null

    /** What this plan saves against paying monthly for as long, "-7,7 %"; null when nothing. */
    val savingLabel: String?
        get() {
            val monthly = MONTHLY.priceCents * months
            if (months <= 1 || priceCents >= monthly) return null
            val percent = (monthly - priceCents).toDouble() / monthly * 100
            return "-${String.format(Locale.ROOT, "%.1f", percent).replace('.', ',')} %"
        }

    companion object {
        val MONTHLY = SubscriptionPlan("monthly", "Mensuel", 1299, 1)
        val YEARLY = SubscriptionPlan("yearly", "Annuel", 14388, 12)
        val ALL = listOf(MONTHLY, YEARLY)

        fun euros(cents: Int): String = "${cents / 100},${(cents % 100).toString().padStart(2, '0')} €"
    }
}
