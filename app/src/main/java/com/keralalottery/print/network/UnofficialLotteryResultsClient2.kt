package com.keralalottery.print.network

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Second unofficial mirror — keralalottery.com.co. Unlike keralalotteries.net, this site keys
 * its URL by lottery name only, not by draw number or date:
 *   /{lottery-name}-lottery-result-today/
 * always shows whatever that lottery's latest draw is, so there's nothing to compute beyond
 * the name slug - no draw-number extrapolation needed here.
 */
object UnofficialLotteryResultsClient2 {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    fun guessUrl(listing: LotteryListing): String {
        val slug = slugify(listing.name)
        return "https://keralalottery.com.co/$slug-lottery-result-today/"
    }

    private fun slugify(raw: String): String =
        raw.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun fetchHtml(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error(
                    if (response.code == 404)
                        "അനൗദ്യോഗിക സൈറ്റ് 2 ഈ ഫലം ഇതുവരെ പ്രസിദ്ധീകരിച്ചിട്ടില്ല - കുറച്ച് കഴിഞ്ഞ് വീണ്ടും ശ്രമിക്കുക, അല്ലെങ്കിൽ മറ്റൊരു സ്രോതസ്സ് ഉപയോഗിക്കുക."
                    else "അനൗദ്യോഗിക സൈറ്റ് 2 ലഭ്യമായില്ല (HTTP ${response.code})."
                )
            }
            return response.body?.string()?.takeIf { it.isNotBlank() } ?: error("പേജിൽ ഉള്ളടക്കമില്ല.")
        }
    }
}
