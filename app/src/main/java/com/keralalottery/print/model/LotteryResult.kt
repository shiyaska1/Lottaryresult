package com.keralalottery.print.model

/** Header block parsed from the top of the official result PDF. */
data class LotteryHeader(
    val lotteryName: String,   // e.g. "SAMRUDHI LOTTERY"
    val drawNumber: String,    // e.g. "SM-67th"
    val drawDate: String,      // e.g. "09/08/2026"
    val drawTime: String,      // e.g. "3:00 PM"
    val venue: String          // e.g. "AT GORKY BHAVAN, NEAR BAKERY JUNCTION, THIRUVANANTHAPURAM"
)

/** A single named winner, used for the 1st/2nd/3rd (bumper) prizes. */
data class Winner(val ticketNumber: String, val place: String)

/**
 * One prize tier row, e.g. "4th Prize-Rs :5000/-" with its list of numbers.
 *
 * [winners] holds full ticket+place entries (1st/2nd/3rd prize).
 * [numbers] holds the plain number/ticket tokens printed under the tier (consolation prize
 * full tickets, or the last-N-digit numbers for 4th prize and below).
 */
data class PrizeTier(
    val label: String,   // "1st Prize", "Cons Prize", "4th Prize", ...
    val amount: String,  // "10000000/-", "1 CRORE", "5000/-", ...
    val winners: List<Winner> = emptyList(),
    val numbers: List<String> = emptyList()
)

data class LotteryResult(
    val header: LotteryHeader,
    val tiers: List<PrizeTier>
)
