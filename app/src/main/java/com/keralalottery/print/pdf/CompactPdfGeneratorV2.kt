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
 * Second one-page layout, same auto-fit-font search as [CompactPdfGenerator] (grow the font
 * from a generous ceiling, shrink until everything fits) but the 4th-9th prize tiers get a
 * short black amount badge - one row tall, horizontal text, no rotation - sitting in the first
 * number slot of the grid instead of a full-width bar above it. The badge only ever costs a
 * single row's height (it shares the first row with numbers rather than getting a row of its
 * own), which is why this format packs tighter than the first one for the same content.
 */
object CompactPdfGeneratorV2 {

    private const val MARGIN = 20f
    private const val GUTTER = 10f
    private const val ROW_LEADING = 1.12f
    private const val HEADER_FIXED_TOP = 46f
    private const val TIER_GAP = 3f
    // Much higher than Format 1's ceiling deliberately: the one-row badge (vs. a full-width bar
    // plus its own gap) made this layout enough more compact that a light draw was hitting 48pt
    // as a hard cap before the page was anywhere near full - the search only ever shrinks from
    // this starting point, so it needs real headroom to find how large a sparse result can
    // actually grow. The width check (a line too wide for the page) still caps it appropriately
    // for a dense draw long before this is ever reached.
    private const val CEILING_FONT = 220f
    private const val MIN_FONT = 2f
    private const val FOOTER_HEIGHT = 16f
    private const val FOOTER_TEXT = "വാട്സ്ആപ്പിൽ ബന്ധപ്പെടുക: 9961128378"
    private const val WAITING_TEXT = "ഫലം ഉടൻ വരും"

    private val A4 = 595f to 842f

    fun generate(result: LotteryResult, companyName: String, outputFile: File, isUnofficial: Boolean = false): File {
        val plan = plan(result, companyName, A4, isUnofficial)
        val (pageW, pageH) = plan.pageSize
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), 1).create()
        val page = document.startPage(pageInfo)
        draw(page.canvas, plan)
        document.finishPage(page)
        FileOutputStream(outputFile).use { document.writeTo(it) }
        document.close()
        return outputFile
    }

    // ---- layout model ------------------------------------------------------

    private class TierRows(val tier: PrizeTier, val columns: Int, val colWidth: Float, val kind: Kind, val boxHeight: Float)
    private enum class Kind { WINNER, CONSOLATION, GRID }

    private class Plan(
        val pageSize: Pair<Float, Float>,
        val companyName: String,
        val result: LotteryResult,
        val numberFontSize: Float,
        val tierRows: List<TierRows>,
        val isUnofficial: Boolean
    )

    private fun kindOf(tier: PrizeTier) = when {
        tier.winners.isNotEmpty() -> Kind.WINNER
        tier.label == "Consolation Prize" -> Kind.CONSOLATION
        else -> Kind.GRID
    }

    /** Row/column of the [index]-th number in a grid tier whose very first slot is occupied by
     * the amount badge instead of a number - shared by plan() (to size the box) and draw() (to
     * place the numbers) so they can never disagree. */
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

    private fun plan(result: LotteryResult, companyName: String, pageSize: Pair<Float, Float>, isUnofficial: Boolean): Plan {
        val (pageW, pageH) = pageSize
        val contentWidth = pageW - MARGIN * 2
        val availableHeight = pageH - MARGIN * 2 - FOOTER_HEIGHT

        var fs = CEILING_FONT
        var rows: List<TierRows> = emptyList()

        while (true) {
            val numberPaint = numberPaint(fs)
            val labelPaint = labelPaint(fs)
            val badgePaint = labelPaint(fs)
            val firstLineGap = -labelPaint.fontMetrics.ascent + 3f
            var bodyHeight = firstLineGap
            var widthOk = true

            rows = result.tiers.map { tier ->
                val kind = kindOf(tier)
                val label = displayLabel(tier)
                val amount = formatAmountNoSuffix(tier.amount)
                when (kind) {
                    Kind.WINNER -> {
                        val fullAmount = CompactPdfGenerator.formatAmount(tier.amount)
                        val winners = tier.winners.ifEmpty { listOf(null) }
                        winners.forEachIndexed { i, winner ->
                            if (labelPaint.measureText(winnerLine(label, fullAmount, winner, i == 0)) > contentWidth) widthOk = false
                        }
                        val n = tier.winners.size.coerceAtLeast(1)
                        bodyHeight += n * (fs + 2f) * ROW_LEADING + TIER_GAP
                        TierRows(tier, columns = 1, colWidth = 0f, kind = kind, boxHeight = 0f)
                    }
                    Kind.CONSOLATION -> {
                        val fullAmount = CompactPdfGenerator.formatAmount(tier.amount)
                        val lines = collapseConsolation(tier.numbers).ifEmpty { listOf("") }
                        lines.forEachIndexed { i, entry ->
                            if (labelPaint.measureText(consolationLine(label, fullAmount, entry, i == 0)) > contentWidth) widthOk = false
                        }
                        bodyHeight += lines.size * (fs + 2f) * ROW_LEADING + TIER_GAP
                        TierRows(tier, columns = 1, colWidth = 0f, kind = kind, boxHeight = 0f)
                    }
                    Kind.GRID -> {
                        val widestNumber = tier.numbers.maxOfOrNull { numberPaint.measureText(it) } ?: 0f
                        val badgeWidth = badgePaint.measureText(amount)
                        val widest = maxOf(widestNumber, badgeWidth)
                        if (widest > contentWidth) widthOk = false
                        val colWidth = widest + GUTTER
                        val columns = if (colWidth <= 0f) 1 else maxOf(1, (contentWidth / colWidth).toInt())
                        val rowCount = gridRowCount(tier.numbers.size, columns)
                        val boxHeight = rowCount * fs * ROW_LEADING
                        bodyHeight += boxHeight + TIER_GAP
                        TierRows(tier, columns = columns, colWidth = colWidth, kind = kind, boxHeight = boxHeight)
                    }
                }
            }

            if (widthOk && HEADER_FIXED_TOP + bodyHeight <= availableHeight) break
            if (fs <= MIN_FONT) break
            fs -= 0.25f
        }

        return Plan(pageSize, companyName, result, fs.coerceAtLeast(MIN_FONT), rows, isUnofficial)
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

        val numberPaint = numberPaint(plan.numberFontSize)
        val labelPaint = labelPaint(plan.numberFontSize)
        val badgeTextPaint = Paint(labelPaint(plan.numberFontSize)).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
        val badgeBg = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        val leftX = MARGIN

        y += -labelPaint.fontMetrics.ascent + 3f

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
                        canvas.drawText(winnerLine(label, fullAmount, winner, i == 0), leftX, y, labelPaint)
                        y += (plan.numberFontSize + 2f) * ROW_LEADING
                    }
                }
                Kind.CONSOLATION -> {
                    val fullAmount = CompactPdfGenerator.formatAmount(tier.amount)
                    val lines = collapseConsolation(tier.numbers).ifEmpty { listOf("") }
                    lines.forEachIndexed { index, entry ->
                        canvas.drawText(consolationLine(label, fullAmount, entry, index == 0), leftX, y, labelPaint)
                        y += (plan.numberFontSize + 2f) * ROW_LEADING
                    }
                }
                Kind.GRID -> {
                    val amountText = formatAmountNoSuffix(tier.amount)
                    val boxTop = y
                    val columns = tr.columns.coerceAtLeast(1)
                    val rowHeight = plan.numberFontSize * ROW_LEADING

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
