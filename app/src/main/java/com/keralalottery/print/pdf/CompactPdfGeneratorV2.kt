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
 * narrow black spine down the left of their own number grid, with the amount rotated to read
 * bottom-to-top, instead of a full-width horizontal bar sitting above the grid. The spine shares
 * its height with the numbers next to it rather than adding a bar's own height on top, so this
 * format packs noticeably tighter than the first one for the same content.
 */
object CompactPdfGeneratorV2 {

    private const val MARGIN = 20f
    private const val GUTTER = 10f
    private const val ROW_LEADING = 1.12f
    private const val HEADER_FIXED_TOP = 46f
    private const val TIER_GAP = 3f
    private const val CEILING_FONT = 48f
    private const val MIN_FONT = 2f
    private const val FOOTER_HEIGHT = 16f
    private const val FOOTER_TEXT = "വാട്സ്ആപ്പിൽ ബന്ധപ്പെടുക: 9961128378"
    private const val WAITING_TEXT = "ഫലം ഉടൻ വരും"
    private const val SPINE_PAD = 4f

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

    private class TierRows(val tier: PrizeTier, val columns: Int, val kind: Kind, val spineWidth: Float, val boxHeight: Float)
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

    /** A grid tier's box must be tall enough for both its own number rows AND its rotated
     * spine label (whose printed length becomes a *height* requirement once rotated 90°) -
     * shared by plan() and draw() so the two can never compute a different box size. */
    private fun gridBoxHeight(fs: Float, spinePaint: Paint, label: String, amount: String, rowCount: Int): Float {
        val rowsHeight = rowCount.coerceAtLeast(1) * fs * ROW_LEADING
        val labelLength = spinePaint.measureText("$label: $amount") + SPINE_PAD * 2f
        return maxOf(rowsHeight, labelLength)
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
            val spinePaint = labelPaint(fs)
            val firstLineGap = -labelPaint.fontMetrics.ascent + 3f
            var bodyHeight = firstLineGap
            var widthOk = true

            rows = result.tiers.map { tier ->
                val kind = kindOf(tier)
                val label = displayLabel(tier)
                val amount = CompactPdfGenerator.formatAmount(tier.amount)
                when (kind) {
                    Kind.WINNER -> {
                        val winners = tier.winners.ifEmpty { listOf(null) }
                        winners.forEachIndexed { i, winner ->
                            if (labelPaint.measureText(winnerLine(label, amount, winner, i == 0)) > contentWidth) widthOk = false
                        }
                        val n = tier.winners.size.coerceAtLeast(1)
                        bodyHeight += n * (fs + 2f) * ROW_LEADING + TIER_GAP
                        TierRows(tier, columns = 1, kind = kind, spineWidth = 0f, boxHeight = 0f)
                    }
                    Kind.CONSOLATION -> {
                        val lines = collapseConsolation(tier.numbers).ifEmpty { listOf("") }
                        lines.forEachIndexed { i, entry ->
                            if (labelPaint.measureText(consolationLine(label, amount, entry, i == 0)) > contentWidth) widthOk = false
                        }
                        bodyHeight += lines.size * (fs + 2f) * ROW_LEADING + TIER_GAP
                        TierRows(tier, columns = 1, kind = kind, spineWidth = 0f, boxHeight = 0f)
                    }
                    Kind.GRID -> {
                        val spineFm = spinePaint.fontMetrics
                        val spineWidth = (spineFm.descent - spineFm.ascent) + SPINE_PAD * 2f
                        val numbersWidth = contentWidth - spineWidth - GUTTER
                        val widest = tier.numbers.maxOfOrNull { numberPaint.measureText(it) } ?: 0f
                        if (widest > numbersWidth) widthOk = false
                        val colWidth = widest + GUTTER
                        val columns = if (colWidth <= 0f) 1 else maxOf(1, (numbersWidth / colWidth).toInt())
                        val count = tier.numbers.size
                        val rowCount = if (count == 0) 0 else (count + columns - 1) / columns
                        val boxHeight = gridBoxHeight(fs, spinePaint, label, amount, rowCount)
                        bodyHeight += boxHeight + TIER_GAP
                        TierRows(tier, columns = columns, kind = kind, spineWidth = spineWidth, boxHeight = boxHeight)
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
        drawFit(canvas, "${h.drawNumber}th", MARGIN, y, thirdWidth, letterheadLeft)
        drawFit(canvas, h.lotteryName, centerX, y, thirdWidth, letterheadCenter)
        drawFit(canvas, h.drawDate, pageW - MARGIN, y, thirdWidth, letterheadRight)
        y += 8f
        canvas.drawLine(MARGIN, y, pageW - MARGIN, y, rulePaint)

        val numberPaint = numberPaint(plan.numberFontSize)
        val labelPaint = labelPaint(plan.numberFontSize)
        val spinePaint = Paint(labelPaint(plan.numberFontSize)).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
        val spineBg = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
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
            val amountText = CompactPdfGenerator.formatAmount(tier.amount)
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
                    // y is the box's TOP edge here (not a text baseline) - the spine and every
                    // number row sit at-or-below it, mirroring the exact height plan() budgeted.
                    val boxTop = y
                    val boxBottom = boxTop + tr.boxHeight
                    canvas.drawRect(MARGIN, boxTop, MARGIN + tr.spineWidth, boxBottom, spineBg)

                    canvas.save()
                    val spineCenterX = MARGIN + tr.spineWidth / 2f
                    val spineCenterY = (boxTop + boxBottom) / 2f
                    canvas.rotate(-90f, spineCenterX, spineCenterY)
                    val spineFm = spinePaint.fontMetrics
                    canvas.drawText("$label: $amountText", spineCenterX, spineCenterY - (spineFm.ascent + spineFm.descent) / 2f, spinePaint)
                    canvas.restore()

                    val columns = tr.columns.coerceAtLeast(1)
                    val numbersLeft = MARGIN + tr.spineWidth + GUTTER
                    val numbersWidth = contentWidth - tr.spineWidth - GUTTER
                    val colWidth = numbersWidth / columns
                    val rowsHeight = (if (tier.numbers.isEmpty()) 1 else (tier.numbers.size + columns - 1) / columns) * plan.numberFontSize * ROW_LEADING
                    // Numbers are vertically centered in the box, so a short grid (a spine tall
                    // enough only for its own rotated label) doesn't leave its numbers pinned to
                    // the top with dead space below them.
                    val rowsTop = boxTop + (tr.boxHeight - rowsHeight) / 2f
                    tier.numbers.forEachIndexed { index, num ->
                        val col = index % columns
                        val row = index / columns
                        val x = numbersLeft + col * colWidth
                        val rowY = rowsTop + (-numberPaint.fontMetrics.ascent) + row * plan.numberFontSize * ROW_LEADING
                        canvas.drawText(num, x, rowY, numberPaint)
                    }
                    y = boxBottom
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
