package com.keralalottery.print.search

import com.keralalottery.print.model.LotteryResult

/** One winning row that matched the searched-for number. */
data class TicketMatch(
    val tierLabel: String,
    val amount: String,
    val number: String,
    val place: String
)

/**
 * Finds every winning ticket/number across all prize tiers that contains [query] anywhere in it
 * - a full ticket number for the bumper/consolation tiers, or the printed last-N-digit number
 * for the lower tiers. A plain substring match, since a ticket buyer usually only remembers part
 * of their number.
 */
fun LotteryResult.findTicketMatches(query: String): List<TicketMatch> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val matches = mutableListOf<TicketMatch>()
    for (tier in tiers) {
        for (winner in tier.winners) {
            if (winner.ticketNumber.contains(q, ignoreCase = true)) {
                matches += TicketMatch(tier.label, tier.amount, winner.ticketNumber, winner.place)
            }
        }
        for (number in tier.numbers) {
            if (number.contains(q, ignoreCase = true)) {
                matches += TicketMatch(tier.label, tier.amount, number, "")
            }
        }
    }
    return matches
}
