package com.keralalottery.print.network

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One row of the results listing on result.keralalotteries.com: a lottery's most recent draw. */
data class LotteryListing(
    val name: String,       // e.g. "SAMRUDHI"
    val drawCode: String,   // e.g. "SM-67"
    val date: String,       // e.g. "09/08/2026"
    val drawSerial: String  // e.g. "75345" - the id viewlotisresult.php expects
)

/**
 * Talks to result.keralalotteries.com, which lists every lottery's most recent draws on its
 * homepage (most-recent-first) and serves each draw's official-style result PDF directly at
 * `viewlotisresult.php?drawserial=<id>`.
 */
object KeralaLotteryResultsClient {

    private const val BASE_URL = "https://result.keralalotteries.com/"
    private const val USER_AGENT = "Mozilla/5.0 (Android) LotteryResultPrint/1.0"

    // Matches rows like:
    // <td ...>SAMRUDHI(SM-67)</td><td ...>09/08/2026</td><td ...><a href="viewlotisresult.php?drawserial=75345" ...
    private val ROW_REGEX = Regex(
        """<td[^>]*>([A-Z][A-Z0-9'\- ]*?)\((\S+?)\)</td>\s*<td[^>]*>(\d{2}/\d{2}/\d{4})</td>\s*<td[^>]*>\s*<a\s+href="viewlotisresult\.php\?drawserial=(\d+)""""
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** One entry per distinct lottery name, each the most recent draw for that lottery. Must run off the main thread. */
    fun fetchLatestDraws(): List<LotteryListing> {
        val request = Request.Builder().url(BASE_URL).header("User-Agent", USER_AGENT).build()
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Could not reach the results site (HTTP ${response.code}).")
            response.body?.string() ?: error("Empty response from the results site.")
        }

        val seen = LinkedHashMap<String, LotteryListing>()
        for (match in ROW_REGEX.findAll(html)) {
            val name = match.groupValues[1].trim()
            if (name !in seen) {
                seen[name] = LotteryListing(
                    name = name,
                    drawCode = match.groupValues[2].trim(),
                    date = match.groupValues[3].trim(),
                    drawSerial = match.groupValues[4].trim()
                )
            }
        }
        if (seen.isEmpty()) {
            error("Could not find any results on the site - its page layout may have changed.")
        }
        return seen.values.toList()
    }

    /** Downloads the official-style result PDF for a draw. Must run off the main thread. */
    fun fetchResultPdf(drawSerial: String): ByteArray {
        val url = "${BASE_URL}viewlotisresult.php?drawserial=$drawSerial"
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Could not download the result PDF (HTTP ${response.code}).")
            return response.body?.bytes() ?: error("Empty PDF response.")
        }
    }
}
