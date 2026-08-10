package com.keralalottery.print.psc

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One row of the official "Latest" feed: a ranked list, short list, interview notice, etc. */
data class PscNotice(val type: String, val title: String, val fileUrl: String?)

/**
 * Scrapes the Kerala Public Service Commission's own "Latest" feed
 * (keralapsc.gov.in/latest), which consolidates every notification type - ranked lists, short
 * lists, interview call-ups, press releases - into one table, newest first. It's a plain
 * server-rendered Drupal "Views" table with stable field classes (views-field-type/-title/
 * -field-file), so no JS rendering is needed to read it.
 */
object PscNoticeFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val BASE_URL = "https://www.keralapsc.gov.in"

    // The site's WAF has been seen returning HTTP 403 for a bare "Mozilla/5.0 ..." User-Agent
    // with no other headers - it reads as a bot even though the exact same request succeeds from
    // a plain desktop/curl client elsewhere. A full, versioned mobile Chrome UA plus the
    // Accept/Accept-Language headers a real browser always sends makes the request look like an
    // actual phone visiting the page.
    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private fun Request.Builder.withBrowserHeaders(): Request.Builder = this
        .header("User-Agent", MOBILE_USER_AGENT)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        .header("Accept-Language", "en-US,en;q=0.9")

    private val ROW_REGEX = Regex("""<tr>([\s\S]*?)</tr>""")
    private val TYPE_REGEX = Regex("""views-field-type"[^>]*>\s*([^<]*?)\s*</td>""")
    private val TITLE_REGEX = Regex("""views-field-title"[^>]*>\s*([^<]*?)\s*</td>""")
    private val FILE_REGEX = Regex("""views-field-field-file"[^>]*>[\s\S]*?href="([^"]+)"""")

    /** Latest notices in the order the site lists them (newest first). Must run off the main thread. */
    fun fetchLatest(): List<PscNotice> {
        val request = Request.Builder()
            .url("$BASE_URL/latest")
            .withBrowserHeaders()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Could not reach the Kerala PSC site (HTTP ${response.code}).")
            val html = response.body?.string().orEmpty()

            val notices = ROW_REGEX.findAll(html).mapNotNull { rowMatch ->
                val row = rowMatch.groupValues[1]
                val type = TYPE_REGEX.find(row)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val title = TITLE_REGEX.find(row)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val file = FILE_REGEX.find(row)?.groupValues?.get(1)?.trim()
                    ?.let { if (it.startsWith("http")) it else "$BASE_URL$it" }
                PscNotice(type, title, file)
            }.toList()

            if (notices.isEmpty()) {
                error("Could not find any notices on the PSC site — its page layout may have changed.")
            }
            return notices
        }
    }

    /** Downloads a notice's PDF (rank list, short list, etc.) into memory. Must run off the main thread. */
    fun downloadPdf(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .withBrowserHeaders()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Could not download the PDF (HTTP ${response.code}).")
            return response.body?.bytes() ?: error("Empty PDF response.")
        }
    }
}
