package com.keralalottery.print.parse

import android.content.Context
import android.net.Uri
import com.keralalottery.print.model.LotteryHeader
import com.keralalottery.print.model.LotteryResult
import com.keralalottery.print.model.PrizeTier
import com.keralalottery.print.model.Winner
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
    private val WINNER = Regex("""(?:\d+\)\s*)?([A-Z]{1,3})\s?(\d{4,6})\s*\(([^)]*)\)""")
    private val FOOTER_LINE = Regex("""^\d{2}/\d{2}/\d{4}\s+\d{2}:\d{2}:\d{2}.*Page\s+\d+""")
    private val DRAW_LINE = Regex(
        """^(.+?LOTTERY)\s+NO\.?\s*(\S+)\s+DRAW held on:-\s*([0-9/]+),?\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val VENUE_LINE = Regex("""^AT\s+.+""")
    private val PREFIX_TOKEN = Regex("""^[A-Z]{1,3}$""")
    private val PREFIX_NUMBER_GLUED = Regex("""([A-Z]{1,3})(\d{4,6})""")
    private val BARE_NUMBER = Regex("""^\d{3,4}$""")
    private const val STOP_MARKER = "the prize winners are advised"

    /** Extracts + parses a PDF the user picked from device storage. Must run off the main thread. */
    fun parsePdf(context: Context, uri: Uri): LotteryResult {
        ensureLoader(context)
        val text = context.contentResolver.openInputStream(uri)?.use { extractText(it) }
            ?: error("Could not open the selected PDF")
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

        val header = LotteryHeader(lotteryName, drawNumber, drawDate, drawTime, venue)
        return LotteryResult(header, tiers.map { it.finalize() })
    }

    private class PendingTier(val key: String) {
        val rawContent = StringBuilder()

        fun finalize(): PrizeTier {
            val text = rawContent.toString().trim().replace(Regex("\\s+"), " ")
            val label = if (key == "Cons") "Consolation Prize" else "$key Prize"
            return when (key) {
                "1st", "2nd", "3rd" -> parseWinnerTier(label, text)
                "Cons" -> parseConsolationTier(label, text)
                else -> parseBareNumberTier(label, text)
            }
        }

        private fun parseWinnerTier(label: String, text: String): PrizeTier {
            val matches = WINNER.findAll(text).toList()
            val winners = matches.map {
                Winner(
                    ticketNumber = "${it.groupValues[1]} ${it.groupValues[2]}",
                    place = it.groupValues[3].trim()
                )
            }
            val amountEnd = matches.firstOrNull()?.range?.first ?: text.length
            val amount = text.substring(0, amountEnd).trim()
            return PrizeTier(label, amount, winners = winners)
        }

        private fun parseConsolationTier(label: String, text: String): PrizeTier {
            val spaced = text.replace(PREFIX_NUMBER_GLUED, "$1 $2")
            val tokens = spaced.split(' ').filter { it.isNotBlank() }
            var i = 0
            val amountTokens = mutableListOf<String>()
            while (i < tokens.size && !PREFIX_TOKEN.matches(tokens[i])) {
                amountTokens += tokens[i]
                i++
            }
            val numbers = mutableListOf<String>()
            while (i < tokens.size - 1) {
                if (PREFIX_TOKEN.matches(tokens[i]) && tokens[i + 1].matches(Regex("^\\d{4,6}$"))) {
                    numbers += "${tokens[i]} ${tokens[i + 1]}"
                    i += 2
                } else {
                    i++
                }
            }
            return PrizeTier(label, amountTokens.joinToString(" "), numbers = numbers)
        }

        private fun parseBareNumberTier(label: String, text: String): PrizeTier {
            val tokens = text.split(' ').filter { it.isNotBlank() }
            var i = 0
            val amountTokens = mutableListOf<String>()
            while (i < tokens.size && !BARE_NUMBER.matches(tokens[i])) {
                amountTokens += tokens[i]
                i++
            }
            val numbers = tokens.subList(i, tokens.size).filter { BARE_NUMBER.matches(it) }
            return PrizeTier(label, amountTokens.joinToString(" "), numbers = numbers)
        }
    }
}
