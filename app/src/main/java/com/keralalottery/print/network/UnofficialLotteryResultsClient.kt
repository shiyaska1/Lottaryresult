package com.keralalottery.print.network

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches a result page from a third-party mirror site the user pastes a link to — used only
 * as a fallback for when the official government portal hasn't published a draw yet, since
 * mirror sites are usually faster.
 */
object UnofficialLotteryResultsClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Could not open that link (HTTP ${response.code}).")
            return response.body?.string()?.takeIf { it.isNotBlank() } ?: error("The page had no content.")
        }
    }
}
