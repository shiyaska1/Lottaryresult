package com.keralalottery.print.gold

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class GoldRateEntry(val date: LocalDate, val ratePerGram: Int)

/**
 * Builds the app's own price history one day at a time — there is no single free source that
 * hands back a year of genuine Kerala 22K rates, so instead each day the app is opened we record
 * whatever AKGSMA is quoting right then, and the 15D/30D/6M/1Y views simply show as much of that
 * as has accumulated so far. Re-fetching on the same calendar date overwrites that day's entry
 * (an intraday revision), it never adds a second row for the same day.
 */
object GoldRateStore {
    private const val FILE_NAME = "gold_rate_history.csv"
    private val DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun loadAll(context: Context): List<GoldRateEntry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return f.readLines()
            .mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size != 2) return@mapNotNull null
                val date = runCatching { LocalDate.parse(parts[0], DATE_FMT) }.getOrNull() ?: return@mapNotNull null
                val rate = parts[1].toIntOrNull() ?: return@mapNotNull null
                GoldRateEntry(date, rate)
            }
            .sortedBy { it.date }
    }

    fun recordToday(context: Context, ratePerGram: Int): List<GoldRateEntry> {
        val today = LocalDate.now()
        val updated = (loadAll(context).filterNot { it.date == today } + GoldRateEntry(today, ratePerGram))
            .sortedBy { it.date }
        file(context).writeText(updated.joinToString("\n") { "${it.date.format(DATE_FMT)},${it.ratePerGram}" })
        return updated
    }
}

enum class GoldPeriod(val label: String, val days: Long) {
    FIVE_DAY("5D", 5),
    FIFTEEN_DAY("15D", 15),
    THIRTY_DAY("30D", 30),
    SIX_MONTH("6M", 182),
    ONE_YEAR("1Y", 365)
}

fun List<GoldRateEntry>.within(period: GoldPeriod): List<GoldRateEntry> {
    val cutoff = LocalDate.now().minusDays(period.days)
    return filter { !it.date.isBefore(cutoff) }
}
