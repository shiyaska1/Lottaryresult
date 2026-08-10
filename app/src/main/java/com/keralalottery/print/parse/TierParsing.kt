package com.keralalottery.print.parse

import com.keralalottery.print.model.PrizeTier
import com.keralalottery.print.model.Winner

/**
 * Shared tier-line parsing rules, used by both [LotteryPdfParser] (the official result PDF)
 * and [UnofficialResultParser] (a pasted mirror-site page) — same rules either way so a result
 * looks identical on the printed page no matter which source it came from.
 */
internal val WINNER = Regex("""(?:\d+\)\s*)?([A-Z]{1,3})\s?(\d{4,6})\s*\(([^)]*)\)""")
private val PREFIX_TOKEN = Regex("""^[A-Z]{1,3}$""")
private val PREFIX_NUMBER_GLUED = Regex("""([A-Z]{1,3})(\d{4,6})""")
private val BARE_NUMBER = Regex("""^\d{3,4}$""")

/** A tier whose content is one or more named winners, e.g. "BT 263322 (THIRUVANANTHAPURAM)". */
internal fun parseWinnerTier(label: String, text: String): PrizeTier {
    val matches = WINNER.findAll(text).toList()
    val winners = matches.map {
        Winner(ticketNumber = "${it.groupValues[1]} ${it.groupValues[2]}", place = it.groupValues[3].trim())
    }
    val amountEnd = matches.firstOrNull()?.range?.first ?: text.length
    val amount = text.substring(0, amountEnd).trim()
    return PrizeTier(label, amount, winners = winners)
}

/** The consolation tier: one full ticket (prefix+number) per remaining series, no place name. */
internal fun parseConsolationTier(label: String, text: String): PrizeTier {
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

/** 4th prize and below: just the last 3-4 digits of the winning tickets. */
internal fun parseBareNumberTier(label: String, text: String): PrizeTier {
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

/** Picks the right rule for a tier by content, not by which tier key it is - some bumper
 * draws list a lower tier as several named winners instead of the usual bare-number list. */
internal fun finalizeTier(key: String, label: String, text: String): PrizeTier = when {
    key == "Cons" -> parseConsolationTier(label, text)
    WINNER.containsMatchIn(text) -> parseWinnerTier(label, text)
    else -> parseBareNumberTier(label, text)
}
