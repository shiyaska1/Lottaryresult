package com.keralalottery.print.network

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** One row of the official results listing: a lottery's most recent draw. */
data class LotteryListing(
    val name: String,      // e.g. "SAMRUDHI"
    val drawCode: String,  // e.g. "SM-67"
    val date: String,      // e.g. "09-08-2026"
    val itemId: String     // GUID the /results/<itemId> download endpoint expects
)

/**
 * Talks to the Kerala Government's own lottery result portal
 * (lotteryagent.kerala.gov.in/result/public/), which lists every lottery's recent draws in a
 * table and serves each draw's official PDF at `/results/<itemId>`.
 *
 * The site gates its own "Download" button behind a small arithmetic CAPTCHA
 * ("10 - 6", solved via a popup) before letting the browser navigate to the download URL.
 * That check is evaluated entirely in the page's own client-side JavaScript - the download
 * endpoint itself does not appear to require the answer - but [fetchResultPdf] still fetches
 * and solves the same challenge the site hands out before downloading, mirroring exactly what
 * a person using the site would do.
 */
object OfficialLotteryResultsClient {

    private const val BASE_URL = "https://www.lotteryagent.kerala.gov.in"
    private const val LISTING_URL = "$BASE_URL/result/public/"
    private const val CAPTCHA_URL = "$BASE_URL/getexprestion/single"
    private const val DOWNLOAD_URL = "$BASE_URL/results/"
    private const val USER_AGENT = "Mozilla/5.0 (Android) LotteryResultPrint/1.0"

    // Matches rows like:
    // <td>STHREE-SAKTHI-10/11/2025 (SS-531)</td><td>04-08-2026</td><td><a ... data-item-id="2010063c-...">
    // The name can itself contain a hyphen/space (e.g. "STHREE-SAKTHI"), so the boundary that
    // actually separates it from the trailing junk date is the "-D/D/DDDD" that always follows.
    private val ROW_REGEX = Regex(
        """<td>([A-Z][A-Z \-]*?)-\d{1,2}/\d{1,2}/\d{4}\s*\(([^)]+)\)</td>\s*""" +
            """<td>(\d{2}-\d{2}-\d{4})</td>\s*""" +
            // The trailing closing quote of data-item-id="..." is deliberately left out of the
            // pattern: the hex/dash character class already stops at it, and a raw string
            // literal can't end with a bare " right before its own closing """.
            """<td>\s*<a[^>]*data-item-id="([0-9a-fA-F\-]+)"""
    )
    // Written as normal (non-raw) string literals - the JSON keys' quote marks would otherwise
    // sit directly against a raw string literal's own """ delimiters.
    private val CAPTCHA_RESULT_REGEX = Regex("\"result\"\\s*:\\s*(-?\\d+)")
    private val CAPTCHA_EXPRESSION_REGEX = Regex("\"expression\"\\s*:\\s*\"([^\"]+)\"")
    private val ARITHMETIC_REGEX = Regex("""(-?\d+)\s*([+\-*/])\s*(-?\d+)""")

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** One entry per distinct lottery name, each the most recent draw for that lottery. Must run off the main thread. */
    fun fetchLatestDraws(): List<LotteryListing> {
        val request = Request.Builder().url(LISTING_URL).header("User-Agent", USER_AGENT).build()
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Could not reach the official results site (HTTP ${response.code}).")
            response.body?.string() ?: error("Empty response from the official results site.")
        }

        val seen = LinkedHashMap<String, LotteryListing>()
        for (match in ROW_REGEX.findAll(html)) {
            val name = match.groupValues[1].trim()
            if (name !in seen) {
                seen[name] = LotteryListing(
                    name = name,
                    drawCode = match.groupValues[2].trim(),
                    date = match.groupValues[3].trim(),
                    itemId = match.groupValues[4].trim()
                )
            }
        }
        if (seen.isEmpty()) {
            error("Could not find any results on the official site - its page layout may have changed.")
        }
        return seen.values.toList()
    }

    /** Solves the site's CAPTCHA step, then downloads the PDF for a draw. Must run off the main thread. */
    fun fetchResultPdf(itemId: String): ByteArray {
        solveCaptchaChallenge()
        val request = Request.Builder().url("$DOWNLOAD_URL$itemId").header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Could not download the result PDF (HTTP ${response.code}).")
            return response.body?.bytes() ?: error("Empty PDF response.")
        }
    }

    /** Fetches the CAPTCHA expression the site itself hands out and computes its answer. */
    private fun solveCaptchaChallenge(): Int? {
        val request = Request.Builder()
            .url(CAPTCHA_URL)
            .header("User-Agent", USER_AGENT)
            .post("".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        } ?: return null

        CAPTCHA_RESULT_REGEX.find(body)?.let { return it.groupValues[1].toIntOrNull() }
        val expression = CAPTCHA_EXPRESSION_REGEX.find(body)?.groupValues?.get(1) ?: return null
        return evaluateArithmetic(expression)
    }

    private fun evaluateArithmetic(expression: String): Int? {
        val m = ARITHMETIC_REGEX.find(expression) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[3].toIntOrNull() ?: return null
        return when (m.groupValues[2]) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> if (b != 0) a / b else null
            else -> null
        }
    }
}
