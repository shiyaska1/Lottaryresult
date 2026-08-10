package com.keralalottery.print.education

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/** One notice from Kerala Pareeksha Bhavan's feed - a rank list, exam result, or notification,
 * with its PDF already decoded to bytes (the API embeds it inline, see [EducationNoticeFetcher]). */
data class EducationNotice(val title: String, val pdfBytes: ByteArray)

/**
 * Fetches the latest notices from Kerala Pareeksha Bhavan (Office of the Commissioner of
 * Government Examinations - runs SSLC/THSLC/KTET/D.El.Ed and other state board exams), the
 * state-board counterpart to the PSC tab.
 *
 * Their site only has one endpoint that actually works: get_latest_notifications, which embeds
 * each notice's full PDF as base64 straight in the JSON response (~450KB per item, ~5MB total
 * for the fixed 10-item feed, no date/category field). The lighter, properly-paginated endpoint
 * their own site's "view all notifications" page calls (get_all_notifications) currently returns
 * HTTP 500 regardless of parameters - broken on their end, not fixable from here. Given that, the
 * app deliberately fetches this only once and caches it (see EducationNoticeStore) rather than
 * re-pulling ~5MB on every tab open.
 */
object EducationNoticeFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val URL = "https://pareekshabhavan.kerala.gov.in/index.php/get_latest_notifications"
    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    /** Must run off the main thread - downloads ~5MB. */
    fun fetchLatest(): List<EducationNotice> {
        val request = Request.Builder()
            .url(URL)
            .header("User-Agent", MOBILE_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .post("".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Could not reach Pareeksha Bhavan (HTTP ${response.code}).")
            val body = response.body?.string().orEmpty()
            val items = org.json.JSONObject(body).optJSONArray("msg") ?: JSONArray()

            val notices = (0 until items.length()).mapNotNull { i ->
                val o = items.getJSONObject(i)
                val title = o.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val dataUri = o.optString("doc")
                val base64 = dataUri.substringAfter(',', missingDelimiterValue = "")
                    .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val bytes = runCatching { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }
                    .getOrNull() ?: return@mapNotNull null
                EducationNotice(title, bytes)
            }

            if (notices.isEmpty()) {
                error("Could not find any notices from Pareeksha Bhavan — its feed may have changed.")
            }
            return notices
        }
    }
}
