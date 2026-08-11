package com.keralalottery.print.parse

import com.keralalottery.print.model.LotteryHeader
import com.keralalottery.print.model.LotteryResult
import com.keralalottery.print.network.LotteryListing

/**
 * Parses a result page from the second unofficial mirror (keralalottery.com.co) into the same
 * [LotteryResult] shape as every other source, using the shared tier-parsing rules (see
 * [TierParsing.kt]) so the printed page comes out identical no matter which source it came
 * from.
 *
 * This site's page never prints the draw code (e.g. "BT-66") anywhere in its own text - only
 * the date. So unlike [UnofficialResultParser], the header here is always built from the
 * already-known [LotteryListing] (name/drawCode/date), and this parser only extracts the tiers.
 */
object UnofficialResultParser2 {

    private val TIER_HEADER = Regex("""^(1st|2nd|3rd|4th|5th|6th|7th|8th|9th|10th)\s*Prize\s*:?$""", RegexOption.IGNORE_CASE)
    private val CONSOLATION_HEADER = Regex("""^Cons(?:olation)?\s*Prize$""", RegexOption.IGNORE_CASE)
    // This site glues the currency symbol straight onto the digits ("₹10000000") rather than
    // giving it its own line, so it's stripped during decoding instead of being matched here.
    private val NOISE_LINE = Regex(
        """^(Download PDF|-{2,})$""",
        RegexOption.IGNORE_CASE
    )
    // Once the result section ends, the page moves into "jump to another lottery" nav buttons
    // labelled with a bare lottery name - never seen inside the actual prize content (winner
    // place names are Kerala districts, not lottery names), so it's a safe place to stop.
    private val STOP_MARKER = Regex(
        """^(SAMRUDHI|KARUNYA|KARUNYA PLUS|SUVARNA KERALAM|DHANALEKSHMI|STHREE SAKTHI|BHAGYATHARA)$""",
        RegexOption.IGNORE_CASE
    )
    private const val DEFAULT_VENUE = "AT GORKY BHAVAN, NEAR BAKERY JUNCTION, THIRUVANANTHAPURAM"

    private class Pending(val key: String, val label: String) {
        val content = StringBuilder()
    }

    fun parseHtml(html: String, listing: LotteryListing): LotteryResult {
        val lines = toLines(html)

        val tiers = mutableListOf<Pending>()
        var current: Pending? = null
        var stopped = false

        for (line in lines) {
            if (stopped) continue
            if (current != null && STOP_MARKER.matches(line)) { stopped = true; continue }
            if (NOISE_LINE.matches(line)) continue

            val tierMatch = TIER_HEADER.find(line)
            if (tierMatch != null) {
                val ord = tierMatch.groupValues[1]
                current = Pending(ord, "$ord Prize").also { tiers += it }
                continue
            }
            if (CONSOLATION_HEADER.matches(line)) {
                current = Pending("Cons", "Consolation Prize").also { tiers += it }
                continue
            }
            if (current == null) continue
            current.content.append(line).append(' ')
        }

        if (tiers.isEmpty()) {
            error("ആ പേജിൽ സമ്മാന വിവരങ്ങളൊന്നും കണ്ടെത്താനായില്ല - ലിങ്ക് ഒരു ഫല പേജാണോ എന്ന് പരിശോധിക്കുക.")
        }

        val prizeTiers = tiers.map { p ->
            val text = p.content.toString().trim().replace(Regex("\\s+"), " ")
            if (p.key == "Cons" || WINNER.containsMatchIn(text)) {
                finalizeTier(p.key, p.label, text)
            } else {
                // Bare-number grid tiers (4th-9th): this site's amount is itself a bare 3-4
                // digit number (e.g. "5000"), indistinguishable from a real ticket number by
                // the shared parser's usual "first non-numeric token(s) = amount" rule - so it
                // is split off explicitly here by position (it's always the first token)
                // before the rest goes through the shared numbers-only parser.
                val tokens = text.split(' ').filter { it.isNotBlank() }
                val amount = tokens.firstOrNull() ?: ""
                val rest = tokens.drop(1).joinToString(" ")
                parseBareNumberTier(p.label, rest).copy(amount = amount)
            }
        }

        val header = LotteryHeader(
            lotteryName = "${listing.name} LOTTERY",
            drawNumber = listing.drawCode,
            drawDate = listing.date,
            drawTime = "",
            venue = DEFAULT_VENUE
        )
        return LotteryResult(header, prizeTiers)
    }

    /** Same "page isn't up / draw not announced yet" placeholder as every other source. */
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

    private fun toLines(html: String): List<String> {
        val noScript = html.replace(Regex("(?is)<script.*?</script>"), " ")
        val noStyle = noScript.replace(Regex("(?is)<style.*?</style>"), " ")
        val withBreaks = noStyle.replace(Regex("<[^>]+>"), "\n")
        val decoded = decodeEntities(withBreaks).replace(' ', ' ').replace("₹", "")
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
