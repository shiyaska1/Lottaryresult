package com.keralalottery.print.network

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches a result page from keralalotteries.net — a mirror site that tends to post partial
 * results (starting with just the 1st prize) well before the official government PDF is
 * published, which is what makes it useful as a fallback while people are waiting.
 *
 * [resolveUrl] is the entry point: it reads the actual "today" link for a lottery straight off
 * the site's own homepage, since that's ground truth - immune to our own draw-number math ever
 * drifting out of sync with the site (a real risk: a skipped/renumbered draw, or the homepage
 * being ahead of what our official listing has extrapolated). [guessUrl] (the previous approach -
 * building the URL directly from the official listing's own name/drawCode/date, since
 * keralalotteries.net uses one fixed, predictable slug per draw) is kept only as a fallback for
 * when the homepage doesn't have a link for that lottery yet.
 */
object UnofficialLotteryResultsClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private const val HOME_URL = "https://www.keralalotteries.net/"

    // Matches every "today" result link the homepage lists, e.g.
    // https://www.keralalotteries.net/2026/08/dhanalekshmi-kerala-lottery-result-dl-65-today-12-08-2026.html
    // - group 1 is the draw code prefix (dl, ss, bt, ...), used to pick the right lottery's link
    // out of the homepage's list of all of them (newest first, so the first match per code is
    // the current one).
    private val TODAY_LINK = Regex(
        """https://www\.keralalotteries\.net/\d{4}/\d{2}/[a-z0-9-]+-kerala-lottery-result-([a-z]{2,3})-\d+-today-\d{2}-\d{2}-\d{4}\.html"""
    )

    /** The actual link the homepage currently has for [listing]'s lottery, or null if the
     * homepage itself couldn't be read or has nothing for this lottery yet. */
    private fun findTodayUrl(listing: LotteryListing): String? {
        val code = listing.drawCode.substringBefore('-').lowercase()
        val html = runCatching {
            val request = Request.Builder().url(HOME_URL).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull() ?: return null
        return TODAY_LINK.findAll(html).firstOrNull { it.groupValues[1].equals(code, ignoreCase = true) }?.value
    }

    /** The URL to actually fetch for [listing]: whatever the homepage itself currently links to
     * for this lottery, falling back to the constructed [guessUrl] only if the homepage doesn't
     * have it yet. */
    fun resolveUrl(listing: LotteryListing): String = findTodayUrl(listing) ?: guessUrl(listing)

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
