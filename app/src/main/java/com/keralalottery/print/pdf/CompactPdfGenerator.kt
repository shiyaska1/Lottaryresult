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
 * Lays the parsed result out on a single dense, bold A4 page, always exactly one page - never
 * more - in a "gazette letterhead" style: a custom shop/company header up top, a letterhead row
 * (draw code / lottery name / date), plain bold rows for the bumper + consolation prizes, and
 * black-bar section headers over a multi-column grid for every other prize tier.
 *
 * The number font size is found by search: start from a generously large ceiling and shrink
 * until the whole result fits both dimensions of the page - every single-line row (bumper
 * winners, the consolation line, each grid's black bar) must fit the content width, and the
 * total of every row's height must fit the page height. Since both only ever grow with font
 * size, the first size that satisfies both is the largest one that does - so a light draw
 * (few winners) fills the page with big text, and a heavy draw (hundreds of numbers) shrinks
 * only as much as it must. There is no smaller page / second page fallback: the floor keeps
 * shrinking until it fits, because landing on A4 in one page is the one constraint that always
 * wins. Everything is solid black - this is meant for a black-and-white laser printer.
 */
object CompactPdfGenerator {

    private const val MARGIN = 20f          // pt; kept small on purpose - no wasted padding
    private const val GUTTER = 10f          // pt between grid columns
    private const val ROW_LEADING = 1.12f   // tight line spacing
    // Fixed part of the header - title + letterhead row + rule - up to but not including the
    // gap before the first body line. Must match the increments draw() applies.
    private const val HEADER_FIXED_TOP = 46f
    private const val TIER_GAP = 3f
    private const val CEILING_FONT = 48f    // a near-empty result grows text up to this, not just a modest default
    private const val MIN_FONT = 2f         // technical floor only - real draws fit well above this
    private const val FOOTER_HEIGHT = 16f   // reserved so body content never sits under the footer note
    private const val FOOTER_TEXT =
        "ഒരു പേജ് പ്രിന്റ് അല്ലെങ്കിൽ വാട്സ്ആപ്പ് വേണമെങ്കിൽ ബന്ധപ്പെടുക: 9961128378"

    private val A4 = 595f to 842f

    fun generate(result: LotteryResult, companyName: String, outputFile: File): File {
        val plan = plan(result, companyName, A4)

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

    private class TierRows(val tier: PrizeTier, val columns: Int, val kind: Kind)
    private enum class Kind { WINNER, CONSOLATION, GRID }

    private class Plan(
        val pageSize: Pair<Float, Float>,
        val companyName: String,
        val result: LotteryResult,
        val numberFontSize: Float,
        val tierRows: List<TierRows>
    )

    private fun kindOf(tier: PrizeTier) = when {
        tier.winners.isNotEmpty() -> Kind.WINNER
        tier.label == "Consolation Prize" -> Kind.CONSOLATION
        else -> Kind.GRID
    }

    private fun plan(result: LotteryResult, companyName: String, pageSize: Pair<Float, Float>): Plan {
        val (pageW, pageH) = pageSize
        val contentWidth = pageW - MARGIN * 2
        val availableHeight = pageH - MARGIN * 2 - FOOTER_HEIGHT

        var fs = CEILING_FONT
        var rows: List<TierRows> = emptyList()

        while (true) {
            val numberPaint = numberPaint(fs)
            val labelPaint = labelPaint(fs)
            val barPaint = labelPaint(fs + 1f)
            // The gap between the header rule and the first body line must clear that line's
            // own ascent - a flat gap works for small fonts but a big one (sparse result) would
            // draw its glyphs up into the letterhead above it.
            val firstLineGap = -labelPaint.fontMetrics.ascent + 3f
            var bodyHeight = firstLineGap
            var widthOk = true

            rows = result.tiers.map { tier ->
                val kind = kindOf(tier)
                val label = displayLabel(tier)
                val amount = formatAmount(tier.amount)
                when (kind) {
                    Kind.WINNER -> {
                        val winners = tier.winners.ifEmpty { listOf(null) }
                        winners.forEachIndexed { i, winner ->
                            if (labelPaint.measureText(winnerLine(label, amount, winner, i == 0)) > contentWidth) {
                                widthOk = false
                            }
                        }
                        val n = tier.winners.size.coerceAtLeast(1)
                        bodyHeight += n * (fs + 2f) * ROW_LEADING + TIER_GAP
                        TierRows(tier, columns = 1, kind = kind)
                    }
                    Kind.CONSOLATION -> {
                        val lines = collapseConsolation(tier.numbers).ifEmpty { listOf("") }
                        lines.forEachIndexed { i, entry ->
                            if (labelPaint.measureText(consolationLine(label, amount, entry, i == 0)) > contentWidth) {
                                widthOk = false
                            }
                        }
                        bodyHeight += lines.size * (fs + 2f) * ROW_LEADING + TIER_GAP
                        TierRows(tier, columns = 1, kind = kind)
                    }
                    Kind.GRID -> {
                        if (barPaint.measureText(barLine(label, amount)) > contentWidth) widthOk = false
                        val widest = tier.numbers.maxOfOrNull { numberPaint.measureText(it) } ?: 0f
                        if (widest > contentWidth) widthOk = false
                        val colWidth = widest + GUTTER
                        val columns = if (colWidth <= 0f) 1 else maxOf(1, (contentWidth / colWidth).toInt())
                        val count = tier.numbers.size
                        val rowCount = if (count == 0) 0 else (count + columns - 1) / columns
                        // The bar is a solid rectangle sized to its own text's full ascent+descent
                        // (not just the leading), plus the first number row needs its own ascent
                        // clearance below the bar - both are exact font-metric distances, not a
                        // flat constant, or small/large fonts overlap the bar on one side or other.
                        val barFm = barPaint.fontMetrics
                        val barPad = 2f
                        val barBlockHeight = (barFm.descent - barFm.ascent) + barPad * 2f
                        val gapToFirstRow = -numberPaint.fontMetrics.ascent + barPad
                        bodyHeight += barBlockHeight + gapToFirstRow + rowCount * fs * ROW_LEADING + TIER_GAP
                        TierRows(tier, columns = columns, kind = kind)
                    }
                }
            }

            if (widthOk && HEADER_FIXED_TOP + bodyHeight <= availableHeight) break
            if (fs <= MIN_FONT) break
            fs -= 0.25f
        }

        return Plan(pageSize, companyName, result, fs.coerceAtLeast(MIN_FONT), rows)
    }

    // ---- formatting helpers ------------------------------------------------

    /** "10000000/-" -> "1,00,00,000/-" (Indian digit grouping). Non-numeric amounts pass through untouched. */
    private fun formatAmount(raw: String): String {
        val m = Regex("""^(\d+)(/-)?$""").find(raw.trim()) ?: return raw
        val digits = m.groupValues[1]
        val suffix = m.groupValues[2]
        if (digits.length <= 3) return digits + suffix
        var rest = digits.dropLast(3)
        val last3 = digits.takeLast(3)
        val groups = mutableListOf<String>()
        while (rest.length > 2) {
            groups.add(0, rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) groups.add(0, rest)
        return groups.joinToString(",") + "," + last3 + suffix
    }

    private fun displayLabel(tier: PrizeTier): String = when (tier.label) {
        "1st Prize" -> "FIRST PRIZE"
        "2nd Prize" -> "SECOND PRIZE"
        "3rd Prize" -> "THIRD PRIZE"
        else -> tier.label.uppercase()
    }

    /** Consolation entries usually share one trailing number - "MA 236935 MB 236935 ..." folds to "{MA MB ...} 236935". */
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

    // Shared text builders - used both to measure (plan) and to draw, so the two never disagree.
    // A tier can have several named winners (bumper draws list some lower tiers that way) - only
    // the first line repeats the label, the rest are indented continuations, same as consolation.
    private fun winnerLine(label: String, amount: String, winner: Winner?, isFirst: Boolean): String {
        val prefix = if (isFirst) "$label: $amount" else "   "
        return if (winner != null) "$prefix      ${winner.ticketNumber} ( ${winner.place} )" else prefix
    }

    private fun consolationLine(label: String, amount: String, entry: String, isFirst: Boolean): String =
        if (isFirst) "$label: $amount   $entry" else "   $entry"

    private fun barLine(label: String, amount: String): String = "$label: $amount"

    // ---- paints --------------------------------------------------------------

    private fun numberPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.BLACK
    }

    private fun labelPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        color = Color.BLACK
    }

    /** Defensive only: plan() already guarantees every line fits at the chosen font size. */
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

        // Classic gazette-style double-line frame around the whole page, well clear of the
        // margin the text starts at so it never collides with content.
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
        // Header identity text is a fixed size (not part of the body's font search), so it gets
        // its own defensive shrink-to-fit against an unusually long company/lottery name.
        drawFit(canvas, plan.companyName.ifBlank { " " }, centerX, y, contentWidth, titlePaint)
        y += 18f
        // Letterhead row: draw code left, lottery name center, date right - like an official gazette.
        // Each third of the row gets its own width budget so long values can't collide.
        val thirdWidth = contentWidth / 3f
        drawFit(canvas, h.drawNumber, MARGIN, y, thirdWidth, letterheadLeft)
        drawFit(canvas, h.lotteryName, centerX, y, thirdWidth, letterheadCenter)
        drawFit(canvas, h.drawDate, pageW - MARGIN, y, thirdWidth, letterheadRight)
        y += 8f
        canvas.drawLine(MARGIN, y, pageW - MARGIN, y, rulePaint)

        val numberPaint = numberPaint(plan.numberFontSize)
        val labelPaint = labelPaint(plan.numberFontSize)
        val barTextPaint = Paint(labelPaint(plan.numberFontSize + 1f)).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        val barPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        val leftX = MARGIN

        // Same ascent-aware gap plan() budgeted for, so the first line never touches the rule.
        y += -labelPaint.fontMetrics.ascent + 3f

        for (tr in plan.tierRows) {
            val tier = tr.tier
            val amountText = formatAmount(tier.amount)
            val label = displayLabel(tier)

            when (tr.kind) {
                Kind.WINNER -> {
                    val winners = tier.winners.ifEmpty { listOf(null) }
                    winners.forEachIndexed { i, winner ->
                        canvas.drawText(winnerLine(label, amountText, winner, i == 0), leftX, y, labelPaint)
                        y += (plan.numberFontSize + 2f) * ROW_LEADING
                    }
                }
                Kind.CONSOLATION -> {
                    val lines = collapseConsolation(tier.numbers).ifEmpty { listOf("") }
                    lines.forEachIndexed { index, entry ->
                        canvas.drawText(consolationLine(label, amountText, entry, index == 0), leftX, y, labelPaint)
                        y += (plan.numberFontSize + 2f) * ROW_LEADING
                    }
                }
                Kind.GRID -> {
                    // y is treated as the bar's TOP edge here (not a text baseline) - the whole
                    // bar is drawn at-or-below it, so it can never paint back up over whatever
                    // came before it. Mirrors the exact math plan() used to budget the height.
                    val fm = barTextPaint.fontMetrics
                    val barPad = 2f
                    val barTop = y
                    val barBottom = barTop + (fm.descent - fm.ascent) + barPad * 2f
                    val textBaseline = barTop + barPad - fm.ascent
                    canvas.drawRect(MARGIN, barTop, pageW - MARGIN, barBottom, barPaint)
                    canvas.drawText(barLine(label, amountText), centerX, textBaseline, barTextPaint)
                    y = barBottom + (-numberPaint.fontMetrics.ascent + barPad)

                    val columns = tr.columns.coerceAtLeast(1)
                    val colWidth = contentWidth / columns
                    tier.numbers.forEachIndexed { index, num ->
                        val col = index % columns
                        if (col == 0 && index != 0) y += plan.numberFontSize * ROW_LEADING
                        val x = leftX + col * colWidth
                        canvas.drawText(num, x, y, numberPaint)
                    }
                    if (tier.numbers.isNotEmpty()) y += plan.numberFontSize * ROW_LEADING
                }
            }
            y += TIER_GAP
        }

        // Small fixed footer note, anchored to the page bottom regardless of how much body
        // content there is - plan() already reserved FOOTER_HEIGHT so it can't collide with it.
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
        }
        drawFit(canvas, FOOTER_TEXT, centerX, pageH - 16f, contentWidth, footerPaint)
    }
}
