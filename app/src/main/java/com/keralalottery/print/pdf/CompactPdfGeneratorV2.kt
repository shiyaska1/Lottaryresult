package com.keralalottery.print.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.keralalottery.print.model.LotteryResult
import com.keralalottery.print.model.PrizeTier
import com.keralalottery.print.model.Winner
import java.io.File
import java.io.FileOutputStream

/**
 * Second one-page layout. Two font sizes are searched independently, not one shared size for
 * everything: the bumper/consolation rows are single long lines ("FIRST PRIZE: 1,00,00,000/-
 * RB 264587 ( MOOVATTUPUZHA )") whose *width* caps how large they can ever get, while the
 * 4th-9th grid tiers wrap into many short rows and are only limited by *height* - sharing one
 * font size meant the grids were stuck at whatever tiny size the long bumper lines forced, with
 * most of the page left empty below them. Now the bumper rows get their own width-bound size
 * first, and the grids get their own size searched to fill whatever height is left over after
 * that, so a light draw's numbers actually grow to use the space instead of sitting stranded in
 * mostly-blank tiers. Each grid tier's amount badge is a short black one-row-tall label in the
 * grid's own first slot (horizontal text, not rotated), so it costs no height of its own either.
 */
object CompactPdfGeneratorV2 {

    private const val MARGIN = 20f
    private const val GUTTER = 10f
    private const val ROW_LEADING = 1.12f
    private const val HEADER_FIXED_TOP = 46f
    private const val TIER_GAP = 3f
    private const val CEILING_FONT = 220f
    private const val MIN_FONT = 2f
    private const val FOOTER_HEIGHT = 16f
    private const val FOOTER_TEXT = "വാട്സ്ആപ്പിൽ ബന്ധപ്പെടുക: 9961128378"
    private const val WAITING_TEXT = "ഫലം ഉടൻ വരും"

    private val A4 = 595f to 842f

    /** [gridFontOverride], when given, replaces the auto-fit search for the grid tiers'
     * (4th-9th prize) font size with this exact value - lets someone nudge it up or down by
     * hand and regenerate if the auto-computed size still leaves a gap they'd rather close
     * themselves, or overshoots. Returns the grid font size actually used, whether it came from
     * the override or the search, so the caller can show it and offer to adjust further. */
    fun generate(
        result: LotteryResult,
        companyName: String,
        outputFile: File,
        isUnofficial: Boolean = false,
        gridFontOverride: Float? = null
    ): Pair<File, Float> {
        val plan = plan(result, companyName, A4, isUnofficial, gridFontOverride)
        val (pageW, pageH) = plan.pageSize
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), 1).create()
        val page = document.startPage(pageInfo)
        draw(page.canvas, plan)
        document.finishPage(page)
        FileOutputStream(outputFile).use { document.writeTo(it) }
        document.close()
        return outputFile to plan.gridFontSize
    }

    // ---- layout model ------------------------------------------------------

    private class TierRows(val tier: PrizeTier, val columns: Int, val colWidth: Float, val kind: Kind, val boxHeight: Float, val fontSize: Float)
    private enum class Kind { WINNER, CONSOLATION, GRID }

    private class Plan(
        val pageSize: Pair<Float, Float>,
        val companyName: String,
        val result: LotteryResult,
        val winnerFontSize: Float,
        val gridFontSize: Float,
        val tierRows: List<TierRows>,
        val isUnofficial: Boolean
    )

    private fun kindOf(tier: PrizeTier) = when {
        tier.winners.isNotEmpty() -> Kind.WINNER
        tier.label == "Consolation Prize" -> Kind.CONSOLATION
        else -> Kind.GRID
    }

    /** Row/column of the [index]-th number in a grid tier whose very first slot is occupied by
     * the amount badge instead of a number - shared by every place that lays out or measures a
     * grid tier so they can never disagree. */
    private fun gridPosition(index: Int, columns: Int): Pair<Int, Int> {
        val firstRowSlots = (columns - 1).coerceAtLeast(0)
        return if (index < firstRowSlots) {
            0 to (index + 1)
        } else {
            val adjusted = index - firstRowSlots
            (1 + adjusted / columns) to (adjusted % columns)
        }
    }

    private fun gridRowCount(count: Int, columns: Int): Int {
        val firstRowSlots = (columns - 1).coerceAtLeast(0)
        if (count <= firstRowSlots) return 1
        val remaining = count - firstRowSlots
        return 1 + (remaining + columns - 1) / columns
    }

    /** Largest font at which every bumper/consolation line still fits the page width - these
     * are single long lines, so only width (never height) ever constrains them. */
    private fun findWinnerFontSize(result: LotteryResult, contentWidth: Float): Float {
        var fs = CEILING_FONT
        while (fs > MIN_FONT) {
            val lp = labelPaint(fs)
            var fits = true
            for (tier in result.tiers) {
                val kind = kindOf(tier)
                if (kind == Kind.GRID) { continue }
                val label = displayLabel(tier)
                val fullAmount = CompactPdfGenerator.formatAmount(tier.amount)
                val linesFit = if (kind == Kind.WINNER) {
                    val winners = tier.winners.ifEmpty { listOf(null) }
                    winners.withIndex().none { (i, w) -> lp.measureText(winnerLine(label, fullAmount, w, i == 0)) > contentWidth }
                } else {
                    val lines = collapseConsolation(tier.numbers).ifEmpty { listOf("") }
                    lines.withIndex().none { (i, e) -> lp.measureText(consolationLine(label, fullAmount, e, i == 0)) > contentWidth }
                }
                if (!linesFit) { fits = false; break }
            }
            if (fits) return fs
            fs -= 0.25f
        }
        return MIN_FONT
    }

    /** Measures one grid tier at [fs] - used both while searching for the grid font size and
     * to build the final row once that size is settled, so the two can never disagree. */
    private fun measureGridTier(tier: PrizeTier, fs: Float, contentWidth: Float): Pair<TierRows, Boolean> {
        val numberPaint = numberPaint(fs)
        val badgePaint = labelPaint(fs)
        val amount = formatAmountNoSuffix(tier.amount)
        val widestNumber = tier.numbers.maxOfOrNull { numberPaint.measureText(it) } ?: 0f
        val badgeWidth = badgePaint.measureText(amount)
        val widest = maxOf(widestNumber, badgeWidth)
        val fits = widest <= contentWidth
        val colWidth = widest + GUTTER
        val columns = if (colWidth <= 0f) 1 else maxOf(1, (contentWidth / colWidth).toInt())
        val rowCount = gridRowCount(tier.numbers.size, columns)
        val boxHeight = rowCount * fs * ROW_LEADING
        return TierRows(tier, columns, colWidth, Kind.GRID, boxHeight, fs) to fits
    }

    /** Largest font at which every grid tier's numbers - all of them together - fit within
     * [availableHeight], the space actually left over once the (usually smaller, width-bound)
     * bumper/consolation rows have taken their share. */
    private fun findGridFontSize(gridTiers: List<PrizeTier>, contentWidth: Float, availableHeight: Float): Float {
        if (gridTiers.isEmpty()) return CEILING_FONT
        var fs = CEILING_FONT
        while (fs > MIN_FONT) {
            var totalHeight = 0f
            var widthOk = true
            for (tier in gridTiers) {
                val (tr, fits) = measureGridTier(tier, fs, contentWidth)
                if (!fits) widthOk = false
                totalHeight += tr.boxHeight + TIER_GAP
            }
            if (widthOk && totalHeight <= availableHeight) return fs
            fs -= 0.25f
        }
        return MIN_FONT
    }

    private fun plan(result: LotteryResult, companyName: String, pageSize: Pair<Float, Float>, isUnofficial: Boolean, gridFontOverride: Float?): Plan {
        val (pageW, pageH) = pageSize
        val contentWidth = pageW - MARGIN * 2
        val availableHeight = pageH - MARGIN * 2 - FOOTER_HEIGHT

        val winnerFs = findWinnerFontSize(result, contentWidth)
        val firstLineGap = -labelPaint(winnerFs).fontMetrics.ascent + 3f

        var nonGridHeight = firstLineGap
        for (tier in result.tiers) {
            when (kindOf(tier)) {
                Kind.WINNER -> nonGridHeight += tier.winners.size.coerceAtLeast(1) * (winnerFs + 2f) * ROW_LEADING + TIER_GAP
                Kind.CONSOLATION -> nonGridHeight += collapseConsolation(tier.numbers).ifEmpty { listOf("") }.size * (winnerFs + 2f) * ROW_LEADING + TIER_GAP
                Kind.GRID -> {}
            }
        }

        // nonGridHeight already includes firstLineGap once (its initial value), so it isn't
        // added again here - HEADER_FIXED_TOP + nonGridHeight + gridContribution <=
        // availableHeight is exactly the same overall budget the single-phase search used to
        // enforce in one pass.
        val remainingHeight = (availableHeight - HEADER_FIXED_TOP - nonGridHeight).coerceAtLeast(0f)
        val gridTiers = result.tiers.filter { kindOf(it) == Kind.GRID }
        val gridFs = gridFontOverride?.coerceIn(MIN_FONT, CEILING_FONT)
            ?: findGridFontSize(gridTiers, contentWidth, remainingHeight)

        val rows = result.tiers.map { tier ->
            when (kindOf(tier)) {
                Kind.WINNER -> TierRows(tier, columns = 1, colWidth = 0f, kind = Kind.WINNER, boxHeight = 0f, fontSize = winnerFs)
                Kind.CONSOLATION -> TierRows(tier, columns = 1, colWidth = 0f, kind = Kind.CONSOLATION, boxHeight = 0f, fontSize = winnerFs)
                Kind.GRID -> measureGridTier(tier, gridFs, contentWidth).first
            }
        }

        return Plan(pageSize, companyName, result, winnerFs, gridFs, rows, isUnofficial)
    }

    // ---- formatting helpers ------------------------------------------------

    /** The grid badge shows a bare amount ("5,000"), no "/-" - the bumper-prize rows above keep
     * the full "1,00,00,000/-" form, only the badge drops the suffix. */
    private fun formatAmountNoSuffix(raw: String): String =
        CompactPdfGenerator.formatAmount(raw).removeSuffix("/-")

    private fun displayLabel(tier: PrizeTier): String = when (tier.label) {
        "1st Prize" -> "FIRST PRIZE"
        "2nd Prize" -> "SECOND PRIZE"
        "3rd Prize" -> "THIRD PRIZE"
        else -> tier.label.uppercase()
    }

    private fun collapseConsolation(numbers: List<String>): List<String> {
        val byNumber = LinkedHashMap<String, MutableList<String>>()
        for (entry in numbers) {
            val parts = entry.trim().split(" ")
            if (parts.size == 2) {
                byNumber.getOrPut(parts[1]) { mutableListOf() }.add(parts[0])
            } else {
                byNumber.getOrPut(entry) { mutableListOf() }
            }
        }
        return byNumber.map { (number, prefixes) ->
            if (prefixes.isEmpty()) number else "{${prefixes.joinToString(" ")}} $number"
        }
    }

    private fun winnerLine(label: String, amount: String, winner: Winner?, isFirst: Boolean): String {
        val prefix = if (isFirst) "$label: $amount" else "   "
        return if (winner != null) "$prefix      ${winner.ticketNumber} ( ${winner.place} )" else prefix
    }

    private fun consolationLine(label: String, amount: String, entry: String, isFirst: Boolean): String =
        if (isFirst) "$label: $amount   $entry" else "   $entry"

    // ---- paints --------------------------------------------------------------

    /** Extra-bold on top of the already-bold monospace face - the grid numbers are meant to
     * read at a glance, so they get a heavier weight than everything else on the page. */
    private fun numberPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.BLACK
        isFakeBoldText = true
    }

    private fun labelPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        color = Color.BLACK
    }

    private fun drawFit(canvas: Canvas, text: String, x: Float, y: Float, maxWidth: Float, basePaint: Paint) {
        val paint = Paint(basePaint)
        val w = paint.measureText(text)
        if (w > maxWidth && w > 0f) paint.textSize *= (maxWidth / w) * 0.98f
        canvas.drawText(text, x, y, paint)
    }

    // ---- drawing ---------------------------------------------------------------

    private fun draw(canvas: Canvas, plan: Plan) {
        val (pageW, pageH) = plan.pageSize
        val contentWidth = pageW - MARGIN * 2
        val centerX = pageW / 2f
        var y = MARGIN

        val borderPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1f }
        canvas.drawRect(8f, 8f, pageW - 8f, pageH - 8f, borderPaint)
        canvas.drawRect(12f, 12f, pageW - 12f, pageH - 12f, borderPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
        }
        val letterheadCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
        }
        val letterheadLeft = Paint(letterheadCenter).apply { textAlign = Paint.Align.LEFT }
        val letterheadRight = Paint(letterheadCenter).apply { textAlign = Paint.Align.RIGHT }
        val rulePaint = Paint().apply { color = Color.BLACK; strokeWidth = 1.5f }

        val h = plan.result.header

        y += 20f
        drawFit(canvas, plan.companyName.ifBlank { " " }, centerX, y, contentWidth, titlePaint)
        y += 18f
        val thirdWidth = contentWidth / 3f
        // h.drawNumber sometimes already carries its own ordinal suffix (the official PDF
        // writes it that way) and sometimes doesn't - appending one here unconditionally used
        // to double up as "SK-65thth" whenever the source already had it, so it's shown as-is.
        drawFit(canvas, h.drawNumber, MARGIN, y, thirdWidth, letterheadLeft)
        drawFit(canvas, h.lotteryName, centerX, y, thirdWidth, letterheadCenter)
        drawFit(canvas, h.drawDate, pageW - MARGIN, y, thirdWidth, letterheadRight)
        y += 8f
        canvas.drawLine(MARGIN, y, pageW - MARGIN, y, rulePaint)

        val leftX = MARGIN
        val winnerLabelPaint = labelPaint(plan.winnerFontSize)
        y += -winnerLabelPaint.fontMetrics.ascent + 3f

        if (plan.tierRows.isEmpty()) {
            val bodyBottom = pageH - MARGIN - FOOTER_HEIGHT
            val waitingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 26f
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
            }
            val fm = waitingPaint.fontMetrics
            val baseline = y + (bodyBottom - y) / 2f - (fm.ascent + fm.descent) / 2f
            drawFit(canvas, WAITING_TEXT, centerX, baseline, contentWidth, waitingPaint)
        }

        for (tr in plan.tierRows) {
            val tier = tr.tier
            val label = displayLabel(tier)

            when (tr.kind) {
                Kind.WINNER -> {
                    val fullAmount = CompactPdfGenerator.formatAmount(tier.amount)
                    val winners = tier.winners.ifEmpty { listOf(null) }
                    winners.forEachIndexed { i, winner ->
                        canvas.drawText(winnerLine(label, fullAmount, winner, i == 0), leftX, y, winnerLabelPaint)
                        y += (tr.fontSize + 2f) * ROW_LEADING
                    }
                }
                Kind.CONSOLATION -> {
                    val fullAmount = CompactPdfGenerator.formatAmount(tier.amount)
                    val lines = collapseConsolation(tier.numbers).ifEmpty { listOf("") }
                    lines.forEachIndexed { index, entry ->
                        canvas.drawText(consolationLine(label, fullAmount, entry, index == 0), leftX, y, winnerLabelPaint)
                        y += (tr.fontSize + 2f) * ROW_LEADING
                    }
                }
                Kind.GRID -> {
                    val amountText = formatAmountNoSuffix(tier.amount)
                    val numberPaint = numberPaint(tr.fontSize)
                    val badgeTextPaint = Paint(labelPaint(tr.fontSize)).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
                    val badgeBg = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }

                    val boxTop = y
                    val columns = tr.columns.coerceAtLeast(1)
                    val rowHeight = tr.fontSize * ROW_LEADING

                    // The badge takes exactly the first column's cell on row 0 - one row tall,
                    // horizontal text, no rotation - so it costs no height of its own; numbers
                    // simply start in the very next slot, same row.
                    canvas.drawRect(MARGIN, boxTop, MARGIN + tr.colWidth - GUTTER, boxTop + rowHeight, badgeBg)
                    val badgeFm = badgeTextPaint.fontMetrics
                    val badgeBaseline = boxTop + rowHeight / 2f - (badgeFm.ascent + badgeFm.descent) / 2f
                    canvas.drawText(amountText, MARGIN + (tr.colWidth - GUTTER) / 2f, badgeBaseline, badgeTextPaint)

                    tier.numbers.forEachIndexed { index, num ->
                        val (row, col) = gridPosition(index, columns)
                        val x = MARGIN + col * tr.colWidth
                        val rowY = boxTop + (-numberPaint.fontMetrics.ascent) + row * rowHeight
                        canvas.drawText(num, x, rowY, numberPaint)
                    }
                    y = boxTop + tr.boxHeight
                }
            }
            y += TIER_GAP
        }

        if (!plan.isUnofficial) {
            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 8f
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
            }
            drawFit(canvas, FOOTER_TEXT, centerX, pageH - 16f, contentWidth, footerPaint)
        }
    }
}
