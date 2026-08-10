package com.keralalottery.print.news

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One headline from a source's RSS feed - title + link back to the original article. Full
 * article text is never fetched or reproduced, only what the publisher puts in its own feed. */
data class NewsItem(val source: String, val title: String, val link: String, val date: String)

/**
 * Pulls the latest headlines from a handful of major Malayalam newspapers' own public RSS
 * feeds. Deliberately a headline aggregator, not a scraper: only title/link/date from each
 * feed are kept, and "Read" opens the source's own page - full article content is copyrighted
 * and never pulled or reproduced here.
 */
object NewsFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private data class Source(val name: String, val url: String)

    private val SOURCES = listOf(
        Source("Mathrubhumi", "https://www.mathrubhumi.com/sitemaps/mathrubhumi/rss"),
        Source("Kerala Kaumudi", "https://keralakaumudi.com/rss/topstories"),
        Source("Madhyamam", "https://www.madhyamam.com/feeds.xml")
    )

    private val ITEM_REGEX = Regex("""<item\b[^>]*>([\s\S]*?)</item>""")
    private val LINK_REGEX = Regex("""<link\b[^>]*>([\s\S]*?)</link>""")
    private val TITLE_REGEX = Regex("""<title\b[^>]*>([\s\S]*?)</title>""")
    private val PUBDATE_REGEX = Regex("""<pubDate\b[^>]*>([\s\S]*?)</pubDate>""")
    private val DATE_ONLY_REGEX = Regex("""\d{1,2}\s+[A-Za-z]{3}\s+\d{4}""")

    /** One entry per source: its headlines (best-effort - a source that fails is simply
     * missing from the result, not a hard error for the whole fetch). Must run off the main thread. */
    fun fetchAll(): Map<String, List<NewsItem>> {
        val result = LinkedHashMap<String, List<NewsItem>>()
        for (source in SOURCES) {
            val items = runCatching { fetchOne(source) }.getOrNull()
            if (!items.isNullOrEmpty()) result[source.name] = items
        }
        if (result.isEmpty()) error("Could not reach any news source - check your connection and try again.")
        return result
    }

    private fun fetchOne(source: Source): List<NewsItem> {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", MOBILE_USER_AGENT)
            .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val xml = response.body?.string().orEmpty()
            return ITEM_REGEX.findAll(xml).mapNotNull { m ->
                val block = m.groupValues[1]
                val title = TITLE_REGEX.find(block)?.groupValues?.get(1)?.let(::cleanXmlText)
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val link = LINK_REGEX.find(block)?.groupValues?.get(1)?.let(::cleanXmlText)
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val rawDate = PUBDATE_REGEX.find(block)?.groupValues?.get(1)?.let(::cleanXmlText).orEmpty()
                val date = DATE_ONLY_REGEX.find(rawDate)?.value ?: rawDate
                NewsItem(source.name, title, link, date)
            }.take(20).toList()
        }
    }

    /** Strips a CDATA wrapper if present and unescapes the handful of XML entities RSS feeds use. */
    private fun cleanXmlText(raw: String): String {
        var v = raw.trim()
        if (v.startsWith("<![CDATA[") && v.endsWith("]]>")) {
            v = v.removePrefix("<![CDATA[").removeSuffix("]]>").trim()
        }
        return v
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .trim()
    }
}
