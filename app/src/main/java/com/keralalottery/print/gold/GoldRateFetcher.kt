package com.keralalottery.print.gold

import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Scrapes today's 22K gold rate (per gram) from the All Kerala Gold & Silver Merchants
 * Association site — the body that actually sets the state's daily retail rate, quoted on
 * their homepage as plain text: "22K916 (1gm) - ₹ 13930".
 */
object GoldRateFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val RATE_REGEX = Regex("""22K\s*916\s*\(1\s*gm\)\s*-\s*₹?\s*([\d,]+)""")

    /** Rate in rupees per gram of 22K (916) gold, as currently published. */
    fun fetchTodayRatePerGram(): Int {
        val request = Request.Builder()
            .url("https://akgsma.com/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Could not reach the rate source (HTTP ${response.code}).")
            val html = response.body?.string().orEmpty()
            val match = RATE_REGEX.find(html)
                ?: error("Could not find today's rate on the source page — it may have changed format.")
            return match.groupValues[1].replace(",", "").trim().toInt()
        }
    }

    // Grabs everything between the header row (identified by its "Price of 1 Pavan Gold" label,
    // since the table itself carries no id/class) and the closing </table> tag.
    private val HISTORY_TABLE_REGEX = Regex(
        """Price of 1 Pavan Gold[\s\S]*?</tr>([\s\S]*?)</table>""",
        RegexOption.IGNORE_CASE
    )
    private val HISTORY_ROW_REGEX = Regex("""<tr[^>]*>([\s\S]*?)</tr>""")
    private val HISTORY_DATE_REGEX = Regex("""(\d{1,2}-[A-Za-z]{3}-\d{2})""")
    // Matches the price ("105760" or, for Highest/Lowest-of-month rows, "1,05,600" inside
    // "Rs. 1,05,600 (Lowest of Month)") while skipping the 1-2 digit day/year in the date cell.
    private val HISTORY_PRICE_REGEX = Regex("""[\d,]{5,}""")
    private val HISTORY_DATE_FMT = DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH)

    /**
     * Backfills past days' 22K rates from KeralaGold's current-month daily table — used only to
     * fill in history [GoldRateStore] hasn't recorded itself yet (AKGSMA has no history endpoint
     * of its own). KeralaGold quotes per 1 Pavan (8 grams), so each figure is divided down to a
     * per-gram rate to line up with [fetchTodayRatePerGram]. A day with more than one row (e.g.
     * "5-Aug-26 (Morning)" / "(Afternoon)") keeps the later revision, since rows run oldest-first.
     */
    fun fetchHistoryFromKeralaGold(): List<GoldRateEntry> {
        val request = Request.Builder()
            .url("https://www.keralagold.com/daily-gold-prices.htm")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Could not reach the history source (HTTP ${response.code}).")
            val html = response.body?.string().orEmpty()
            val tableBody = HISTORY_TABLE_REGEX.find(html)?.groupValues?.get(1)
                ?: error("Could not find the daily rate table on the history source — its page layout may have changed.")

            val byDate = LinkedHashMap<LocalDate, Int>()
            for (row in HISTORY_ROW_REGEX.findAll(tableBody)) {
                val cell = row.groupValues[1]
                val date = HISTORY_DATE_REGEX.find(cell)?.groupValues?.get(1)
                    ?.let { runCatching { LocalDate.parse(it, HISTORY_DATE_FMT) }.getOrNull() }
                    ?: continue
                val pavanPrice = HISTORY_PRICE_REGEX.findAll(cell).lastOrNull()
                    ?.value?.replace(",", "")?.toIntOrNull() ?: continue
                byDate[date] = Math.round(pavanPrice / 8.0).toInt()
            }
            return byDate.map { (date, rate) -> GoldRateEntry(date, rate) }
        }
    }
}
