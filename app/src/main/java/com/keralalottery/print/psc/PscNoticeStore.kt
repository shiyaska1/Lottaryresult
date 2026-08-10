package com.keralalottery.print.psc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Caches the last successfully fetched notices on disk. The PSC tab shows this immediately on
 * open instead of a blank spinner, and keeps showing it if a refresh fails (a WAF block, a
 * network hiccup) rather than replacing a working list with an empty error screen.
 */
object PscNoticeStore {
    private const val FILE_NAME = "psc_notices_cache.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun loadCached(context: Context): List<PscNotice> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(f.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                PscNotice(
                    type = o.getString("type"),
                    title = o.getString("title"),
                    fileUrl = if (o.isNull("fileUrl")) null else o.getString("fileUrl")
                )
            }
        }.getOrElse { emptyList() }
    }

    fun save(context: Context, notices: List<PscNotice>) {
        val array = JSONArray()
        notices.forEach { n ->
            array.put(
                JSONObject().apply {
                    put("type", n.type)
                    put("title", n.title)
                    put("fileUrl", n.fileUrl)
                }
            )
        }
        file(context).writeText(array.toString())
    }
}
