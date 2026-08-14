package com.keralalottery.print.search

import com.keralalottery.print.model.LotteryResult

/** One winning row that matched one of the searched-for numbers. */
data class TicketMatch(
    val matchedQuery: String,
    val tierLabel: String,
    val amount: String,
    val number: String,
    val place: String
)

/**
 * Finds every winning ticket/number across all prize tiers that contains any of [queries]
 * anywhere in it - a full ticket number for the bumper/consolation tiers, or the printed
 * last-N-digit number for the lower tiers. A plain substring match, since a ticket buyer usually
 * only remembers part of their number. Supports several numbers at once (e.g. one search for
 * every ticket someone bought) - each match records which entered number it satisfied.
 */
fun LotteryResult.findTicketMatches(queries: List<String>): List<TicketMatch> {
    val qs = queries.map { it.trim() }.filter { it.isNotEmpty() }
    if (qs.isEmpty()) return emptyList()
    val matches = mutableListOf<TicketMatch>()
    for (tier in tiers) {
        for (winner in tier.winners) {
            val q = qs.firstOrNull { winner.ticketNumber.contains(it, ignoreCase = true) }
            if (q != null) matches += TicketMatch(q, tier.label, tier.amount, winner.ticketNumber, winner.place)
        }
        for (number in tier.numbers) {
            val q = qs.firstOrNull { number.contains(it, ignoreCase = true) }
            if (q != null) matches += TicketMatch(q, tier.label, tier.amount, number, "")
        }
    }
    return matches
}
