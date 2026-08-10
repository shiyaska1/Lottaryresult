package com.keralalottery.print.gold

import okhttp3.OkHttpClient
import okhttp3.Request
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
}
