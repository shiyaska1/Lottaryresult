package com.keralalottery.print.network

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Kerala's fixed weekly lottery schedule - every day of the week always draws the same
 * lottery, so today's expected lottery (name + code) is knowable without needing the official
 * listing to have posted a row for it yet. Used only for the "unofficial" checkbox: it should
 * always check *today's* actual draw, not whatever the official listing's dropdown happens to
 * default to (which can still be showing yesterday's if the official site itself hasn't listed
 * today's draw yet).
 */
object KeralaLotterySchedule {
    private val SCHEDULE = mapOf(
        DayOfWeek.SUNDAY to ("SAMRUDHI" to "SM"),
        DayOfWeek.MONDAY to ("BHAGYATHARA" to "BT"),
        DayOfWeek.TUESDAY to ("STHREE SAKTHI" to "SS"),
        DayOfWeek.WEDNESDAY to ("DHANALEKSHMI" to "DL"),
        DayOfWeek.THURSDAY to ("KARUNYA PLUS" to "KN"),
        DayOfWeek.FRIDAY to ("SUVARNA KERALAM" to "SK"),
        DayOfWeek.SATURDAY to ("KARUNYA" to "KR")
    )
    private val DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    /** [officialListings] is whatever the official portal's listing table currently has - one
     * row per lottery, each that lottery's most recent draw. If it already includes today's row
     * for today's lottery, that's returned as-is; otherwise one is synthesized from that
     * lottery's last known draw number + 1 and today's real date, since Kerala's draw numbers
     * for a given lottery always increase by exactly one each week. Returns null only if the
     * official listing has no entry at all for today's lottery to extrapolate from. */
    fun todaysListing(officialListings: List<LotteryListing>): LotteryListing? {
        val today = LocalDate.now()
        val (name, code) = SCHEDULE[today.dayOfWeek] ?: return null
        val todayDate = today.format(DATE_FMT)

        val known = officialListings.find { it.drawCode.startsWith("$code-") } ?: return null
        if (known.date == todayDate) return known

        val num = known.drawCode.substringAfter('-').toIntOrNull() ?: return null
        return known.copy(name = name, drawCode = "$code-${num + 1}", date = todayDate)
    }
}
