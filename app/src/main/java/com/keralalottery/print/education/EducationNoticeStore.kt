package com.keralalottery.print.education

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-disk cache of the last fetched education notices (title + PDF bytes, base64-encoded for the
 * JSON file). The whole point: the API behind [EducationNoticeFetcher] costs ~5MB per fetch, so
 * the tab loads this cache on open and only calls the network again when the user explicitly
 * asks - never automatically on every tab open.
 */
object EducationNoticeStore {
    private const val FILE_NAME = "education_notices_cache.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun loadCached(context: Context): List<EducationNotice> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(f.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                EducationNotice(
                    title = o.getString("title"),
                    pdfBytes = Base64.decode(o.getString("pdfBase64"), Base64.DEFAULT)
                )
            }
        }.getOrElse { emptyList() }
    }

    fun save(context: Context, notices: List<EducationNotice>) {
        val array = JSONArray()
        notices.forEach { n ->
            array.put(
                JSONObject().apply {
                    put("title", n.title)
                    put("pdfBase64", Base64.encodeToString(n.pdfBytes, Base64.DEFAULT))
                }
            )
        }
        file(context).writeText(array.toString())
    }
}
