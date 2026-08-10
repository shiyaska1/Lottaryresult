package com.keralalottery.print.network

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches a result page from keralalotteries.net — a mirror site that tends to post partial
 * results (starting with just the 1st prize) well before the official government PDF is
 * published, which is what makes it useful as a fallback while people are waiting.
 *
 * Rather than searching for the link (fragile, and scraping Google's result pages is against
 * their terms of service), the URL is built directly: keralalotteries.net uses one fixed,
 * predictable slug for every draw -
 *   /{year}/{month}/{lottery-name}-kerala-lottery-result-{draw-code}-today-{dd-mm-yyyy}.html
 * - built from the exact same name/code/date the official portal's own listing already gives
 * us, so there is nothing to search for: every field in the URL is already in hand.
 */
object UnofficialLotteryResultsClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /** Builds the expected keralalotteries.net URL for a draw from the official listing's own
     * name/drawCode/date - e.g. name="SAMRUDHI" drawCode="SM-67" date="09-08-2026" ->
     * ".../2026/08/samrudhi-kerala-lottery-result-sm-67-today-09-08-2026.html". */
    fun guessUrl(listing: LotteryListing): String {
        val parts = listing.date.split("-")
        require(parts.size == 3) { "Unexpected date format: ${listing.date}" }
        val (day, month, year) = parts
        val nameSlug = slugify(listing.name)
        val codeSlug = slugify(listing.drawCode)
        return "https://www.keralalotteries.net/$year/$month/$nameSlug-kerala-lottery-result-$codeSlug-today-$day-$month-$year.html"
    }

    private fun slugify(raw: String): String =
        raw.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun fetchHtml(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error(
                    if (response.code == 404)
                        "അനൗദ്യോഗിക സൈറ്റ് ഈ ഫലം ഇതുവരെ പ്രസിദ്ധീകരിച്ചിട്ടില്ല - കുറച്ച് കഴിഞ്ഞ് വീണ്ടും ശ്രമിക്കുക, അല്ലെങ്കിൽ ഔദ്യോഗിക സ്രോതസ്സ് ഉപയോഗിക്കുക."
                    else "അനൗദ്യോഗിക സൈറ്റ് ലഭ്യമായില്ല (HTTP ${response.code})."
                )
            }
            return response.body?.string()?.takeIf { it.isNotBlank() } ?: error("പേജിൽ ഉള്ളടക്കമില്ല.")
        }
    }
}
