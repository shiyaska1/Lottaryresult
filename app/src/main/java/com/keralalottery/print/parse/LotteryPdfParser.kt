package com.keralalottery.print.parse

import android.content.Context
import android.net.Uri
import com.keralalottery.print.model.LotteryHeader
import com.keralalottery.print.model.LotteryResult
import com.keralalottery.print.model.PrizeTier
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Parses the official Kerala State Lotteries result PDF (the standard weekly-draw layout: a
 * bumper 1st/2nd/3rd prize, a consolation prize, then 4th-9th prize tiers listing the last
 * 3-4 digits of winning ticket numbers) into a structured [LotteryResult].
 *
 * The official PDF lays each prize tier out as a table row: the label+amount in the left
 * column and the winning numbers flowing left-to-right, wrapping onto further rows with a
 * blank label column. Extracting text with position sorting reproduces that as one text line
 * per visual row, which is what the regexes below expect.
 */
object LotteryPdfParser {

    private var loaderInitialized = false

    private val TIER_START = Regex("""^(\d+(?:st|nd|rd|th)|Cons)\s*Prize\s*-?\s*Rs\s*:\s*(.*)$""")
    private val FOOTER_LINE = Regex("""^\d{2}/\d{2}/\d{4}\s+\d{2}:\d{2}:\d{2}.*Page\s+\d+""")
    private val DRAW_LINE = Regex(
        """^(.+?LOTTERY)\s+NO\.?\s*(\S+)\s+DRAW held on:-\s*([0-9/]+),?\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val VENUE_LINE = Regex("""^AT\s+.+""")
    private const val STOP_MARKER = "the prize winners are advised"

    /** Extracts + parses a PDF the user picked from device storage. Must run off the main thread. */
    fun parsePdf(context: Context, uri: Uri): LotteryResult {
        ensureLoader(context)
        val text = context.contentResolver.openInputStream(uri)?.use { extractText(it) }
            ?: error("തിരഞ്ഞെടുത്ത PDF തുറക്കാൻ കഴിഞ്ഞില്ല")
        return parseText(text)
    }

    /** Extracts + parses a PDF already downloaded into memory. Must run off the main thread. */
    fun parsePdfBytes(context: Context, bytes: ByteArray): LotteryResult {
        ensureLoader(context)
        val text = ByteArrayInputStream(bytes).use { extractText(it) }
        return parseText(text)
    }

    private fun ensureLoader(context: Context) {
        if (!loaderInitialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            loaderInitialized = true
        }
    }

    private fun extractText(input: InputStream): String {
        val builder = StringBuilder()
        PDDocument.load(input).use { doc ->
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                startPage = 1
                endPage = doc.numberOfPages
            }
            builder.append(stripper.getText(doc))
        }
        return builder.toString()
    }

    fun parseText(rawText: String): LotteryResult {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        var lotteryName = ""
        var drawNumber = ""
        var drawDate = ""
        var drawTime = ""
        var venue = ""
        val tiers = mutableListOf<PendingTier>()
        var current: PendingTier? = null
        var stopped = false

        for (line in lines) {
            if (stopped) continue
            if (line.lowercase().contains(STOP_MARKER)) {
                stopped = true
                continue
            }
            if (FOOTER_LINE.containsMatchIn(line)) continue
            if (line.equals("FOR THE TICKETS ENDING WITH THE FOLLOWING NUMBERS", ignoreCase = true)) continue

            val drawMatch = DRAW_LINE.find(line)
            if (drawMatch != null) {
                lotteryName = drawMatch.groupValues[1].trim()
                drawNumber = drawMatch.groupValues[2].trim()
                drawDate = drawMatch.groupValues[3].trim()
                drawTime = drawMatch.groupValues[4].trim()
                continue
            }
            if (current == null && venue.isEmpty() && VENUE_LINE.matches(line)) {
                venue = line
                continue
            }

            val tierMatch = TIER_START.find(line)
            if (tierMatch != null) {
                val pending = PendingTier(tierMatch.groupValues[1])
                pending.rawContent.append(tierMatch.groupValues[2]).append(' ')
                tiers += pending
                current = pending
                continue
            }

            // Boilerplate before the first tier starts (masthead, phone numbers, ...).
            if (current == null) continue

            current.rawContent.append(line).append(' ')
        }

        // A draw not yet held sometimes still has a PDF up (a placeholder, or last draw's page
        // not yet replaced) with no prize tiers in it at all - printing that would just be a
        // near-blank page with a header and nothing else, which looks like a bug rather than
        // "not published yet". Fail clearly instead so the app can say so.
        if (tiers.isEmpty()) {
            error("ഈ PDF-ൽ ഇതുവരെ ഫലം ഇല്ല - ഔദ്യോഗിക ഫലം പ്രസിദ്ധീകരിച്ചിട്ടില്ലായിരിക്കാം. കുറച്ച് കഴിഞ്ഞ് വീണ്ടും ശ്രമിക്കുക.")
        }

        val header = LotteryHeader(lotteryName, drawNumber, drawDate, drawTime, venue)
        return LotteryResult(header, tiers.map { it.finalize() })
    }

    private class PendingTier(val key: String) {
        val rawContent = StringBuilder()

        fun finalize(): PrizeTier {
            val text = rawContent.toString().trim().replace(Regex("\\s+"), " ")
            val label = if (key == "Cons") "Consolation Prize" else "$key Prize"
            return finalizeTier(key, label, text)
        }
    }
}
