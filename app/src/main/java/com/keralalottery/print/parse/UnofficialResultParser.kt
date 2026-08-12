package com.keralalottery.print.parse

import com.keralalottery.print.model.LotteryHeader
import com.keralalottery.print.model.LotteryResult
import com.keralalottery.print.model.PrizeTier
import com.keralalottery.print.network.LotteryListing

/**
 * Parses a result page from a pasted mirror-site link (e.g. keralalotteries.net) into the same
 * [LotteryResult] shape [LotteryPdfParser] produces from the official PDF, using the exact same
 * tier-parsing rules (see [TierParsing.kt]) so the printed page comes out identical either way -
 * only the source of the numbers differs, never the layout.
 */
object UnofficialResultParser {

    private val HEADER_LINE = Regex(
        """Kerala Lottery Date of Draw:\s*([0-9/]+)\s+(.+?)\s+([A-Za-z]{2,3})\s+(\d+)\s+Winners Numbers""",
        RegexOption.IGNORE_CASE
    )
    private val TIME_LINE = Regex("""Kerala Lottery Result Live\s*@\s*([\d:apmAPM]+)""")
    private val VENUE_HINT = Regex("""\bat\s+([A-Za-z0-9 ,.'-]+?Thiruvananthapuram)""", RegexOption.IGNORE_CASE)
    private const val DEFAULT_VENUE = "AT GORKY BHAVAN, NEAR BAKERY JUNCTION, THIRUVANANTHAPURAM"

    // The site used to print the tier label alone on its own line, with the amount as a
    // separate following line - it now glues the amount onto the same line/span as the label
    // ("1st Prize Rs.1,00,00,000/- [1 Crore]"), so this only anchors the label as a prefix and
    // captures whatever follows it as the first piece of that tier's content, instead of
    // requiring the whole line to be nothing but the bare label.
    private val TIER_HEADER = Regex("""^(1st|2nd|3rd|4th|5th|6th|7th|8th|9th|10th)\s*Prize\b\s*:?\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val CONSOLATION_HEADER = Regex("""^Consolation Prize\b\s*:?\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val NOISE_LINE = Regex(
        """^(₹|-{2,}|\(Common to all series\)|\(Remaining all series\)|\(Last four digits.*\)|Agent Name\s*:.*|Agency No\.?\s*:.*|For the tickets ending.*)$""",
        RegexOption.IGNORE_CASE
    )
    private const val STOP_MARKER = "repeated draw numbers"

    private class Pending(val key: String, val label: String) {
        val content = StringBuilder()
    }

    /** [html] is the raw page source; entities and tags are stripped here, not by the caller. */
    fun parseHtml(html: String): LotteryResult {
        val lines = toLines(html)
        val fullText = lines.joinToString(" ")

        var lotteryName = ""
        var drawNumber = ""
        var drawDate = ""
        var drawTime = ""
        var venue = DEFAULT_VENUE

        HEADER_LINE.find(fullText)?.let { m ->
            drawDate = m.groupValues[1].trim()
            lotteryName = m.groupValues[2].trim().uppercase() + " LOTTERY"
            drawNumber = "${m.groupValues[3].uppercase()}-${m.groupValues[4]}"
        }
        TIME_LINE.find(fullText)?.let { drawTime = it.groupValues[1].trim() }
        VENUE_HINT.find(fullText)?.let { venue = "AT " + it.groupValues[1].trim().uppercase() }

        val tiers = mutableListOf<Pending>()
        var current: Pending? = null
        var stopped = false

        for (line in lines) {
            if (stopped) continue
            if (line.lowercase().contains(STOP_MARKER)) { stopped = true; continue }
            if (NOISE_LINE.matches(line)) continue

            val tierMatch = TIER_HEADER.find(line)
            if (tierMatch != null) {
                val ord = tierMatch.groupValues[1]
                current = Pending(ord, "$ord Prize").also { tiers += it }
                tierMatch.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let { current!!.content.append(it).append(' ') }
                continue
            }
            val consMatch = CONSOLATION_HEADER.find(line)
            if (consMatch != null) {
                current = Pending("Cons", "Consolation Prize").also { tiers += it }
                consMatch.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { current!!.content.append(it).append(' ') }
                continue
            }
            if (current == null) continue
            current.content.append(line).append(' ')
        }

        // No tiers at all: either this genuinely isn't a result page (header never matched
        // either, so nothing here is trustworthy - a real error), or it's the right page for
        // today's draw, published early, with the draw simply not announced yet - in which
        // case an empty tier list is the correct, honest answer, not a failure.
        if (tiers.isEmpty() && lotteryName.isBlank()) {
            error("ആ പേജിൽ സമ്മാന വിവരങ്ങളൊന്നും കണ്ടെത്താനായില്ല - ലിങ്ക് ഒരു ഫല പേജാണോ എന്ന് പരിശോധിക്കുക.")
        }

        val prizeTiers = tiers.map { p ->
            val text = p.content.toString().trim().replace(Regex("\\s+"), " ")
            val tier = finalizeTier(p.key, p.label, text)
            // The mirror site writes amounts like "1,00,00,000/- [1 Crore]" - already
            // Indian-grouped like our own formatter would produce, just with an extra note.
            tier.copy(amount = tier.amount.replace(Regex("""\[[^]]*]"""), "").trim())
        }

        val header = LotteryHeader(
            lotteryName.ifBlank { "KERALA STATE LOTTERY" },
            drawNumber, drawDate, drawTime, venue
        )
        return LotteryResult(header, prizeTiers)
    }

    /** Used when the unofficial page itself isn't reachable yet at all (e.g. a 404 - the
     * mirror site hasn't even published the article yet, ahead of the draw). The header is
     * built from the official listing directly, since that's already known regardless of
     * whether the mirror page exists, and the empty tier list renders the same "result coming
     * soon" placeholder as a page that loaded but had nothing on it yet. */
    fun waitingResult(listing: LotteryListing): LotteryResult {
        val header = LotteryHeader(
            lotteryName = "${listing.name} LOTTERY",
            drawNumber = listing.drawCode,
            drawDate = listing.date,
            drawTime = "",
            venue = DEFAULT_VENUE
        )
        return LotteryResult(header, emptyList())
    }

    /** Strips scripts/styles/tags to newlines (so adjacent inline elements never glue together
     * into one token) and decodes the handful of HTML entities these pages actually use. */
    private fun toLines(html: String): List<String> {
        val noScript = html.replace(Regex("(?is)<script.*?</script>"), " ")
        val noStyle = noScript.replace(Regex("(?is)<style.*?</style>"), " ")
        val withBreaks = noStyle.replace(Regex("<[^>]+>"), "\n")
        val decoded = decodeEntities(withBreaks).replace(' ', ' ')
        return decoded.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun decodeEntities(s: String): String {
        var out = s.replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
        out = out.replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }
        return out
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }
}
