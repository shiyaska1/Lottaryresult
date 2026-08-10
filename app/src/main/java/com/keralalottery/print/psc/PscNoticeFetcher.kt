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

    private val ROW_REGEX = Regex("""<tr>([\s\S]*?)</tr>""")
    private val TYPE_REGEX = Regex("""views-field-type"[^>]*>\s*([^<]*?)\s*</td>""")
    private val TITLE_REGEX = Regex("""views-field-title"[^>]*>\s*([^<]*?)\s*</td>""")
    private val FILE_REGEX = Regex("""views-field-field-file"[^>]*>[\s\S]*?href="([^"]+)"""")

    /** Latest notices in the order the site lists them (newest first). Must run off the main thread. */
    fun fetchLatest(): List<PscNotice> {
        val request = Request.Builder()
            .url("$BASE_URL/latest")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
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
}
