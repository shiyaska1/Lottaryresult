package com.keralalottery.print.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.keralalottery.print.model.LotteryResult
import com.keralalottery.print.model.PrizeTier
import java.io.File
import java.io.FileOutputStream

/**
 * Lays the parsed result out on a single dense, bold page: a custom shop/company header up
 * top, then every prize tier packed into a multi-column grid. The number font size is chosen
 * by search (shrinking from a comfortably large starting size) so the whole result lands on
 * exactly one page; if even the smallest readable size can't fit on A4, the page escalates to
 * A3 rather than spilling onto a second page or shrinking past legibility.
 */
object CompactPdfGenerator {

    private const val MARGIN = 20f          // pt; kept small on purpose - no wasted padding
    private const val GUTTER = 10f          // pt between grid columns
    private const val ROW_LEADING = 1.12f   // tight line spacing
    private const val HEADER_HEIGHT = 84f   // must match the increments drawHeader() applies
    private const val TIER_GAP = 3f
    private const val START_FONT = 13f
    private const val FLOOR_FONT = 5f

    private val A4 = 595f to 842f
    private val A3 = 842f to 1191f

    fun generate(result: LotteryResult, companyName: String, outputFile: File): File {
        var plan = plan(result, companyName, A4)
        if (!plan.fits) plan = plan(result, companyName, A3)

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

    private class TierRows(val tier: PrizeTier, val columns: Int, val isWinnerTier: Boolean)

    private class Plan(
        val pageSize: Pair<Float, Float>,
        val companyName: String,
        val result: LotteryResult,
        val numberFontSize: Float,
        val tierRows: List<TierRows>,
        val fits: Boolean
    )

    private fun plan(result: LotteryResult, companyName: String, pageSize: Pair<Float, Float>): Plan {
        val (pageW, pageH) = pageSize
        val contentWidth = pageW - MARGIN * 2
        val availableHeight = pageH - MARGIN * 2

        var fs = START_FONT
        var fits = false
        var rows: List<TierRows> = emptyList()

        while (true) {
            val numberPaint = numberPaint(fs)
            var bodyHeight = 0f
            rows = result.tiers.map { tier ->
                val isWinnerTier = tier.winners.isNotEmpty()
                val tierLabelHeight = (fs + 1f) * ROW_LEADING
                if (isWinnerTier) {
                    val n = tier.winners.size.coerceAtLeast(1)
                    bodyHeight += tierLabelHeight + n * (fs + 3f) * ROW_LEADING + TIER_GAP
                    TierRows(tier, columns = 1, isWinnerTier = true)
                } else {
                    val widest = tier.numbers.maxOfOrNull { numberPaint.measureText(it) } ?: 0f
                    val colWidth = widest + GUTTER
                    val columns = if (colWidth <= 0f) 1 else maxOf(1, (contentWidth / colWidth).toInt())
                    val count = tier.numbers.size
                    val rowCount = if (count == 0) 0 else (count + columns - 1) / columns
                    bodyHeight += tierLabelHeight + rowCount * fs * ROW_LEADING + TIER_GAP
                    TierRows(tier, columns = columns, isWinnerTier = false)
                }
            }
            if (HEADER_HEIGHT + bodyHeight <= availableHeight) {
                fits = true
                break
            }
            if (fs <= FLOOR_FONT) break
            fs -= 0.25f
        }

        return Plan(pageSize, companyName, result, fs.coerceAtLeast(FLOOR_FONT), rows, fits)
    }

    // ---- paints --------------------------------------------------------------

    private fun numberPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.BLACK
    }

    private fun labelPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size + 1f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.BLACK
    }

    // ---- drawing ---------------------------------------------------------------

    private fun draw(canvas: Canvas, plan: Plan) {
        val (pageW, _) = plan.pageSize
        val contentWidth = pageW - MARGIN * 2
        val centerX = pageW / 2f
        var y = MARGIN

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f
            typeface = Typeface.DEFAULT
            color = Color.DKGRAY
            textAlign = Paint.Align.CENTER
        }
        val disclaimerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            color = Color.DKGRAY
            textAlign = Paint.Align.CENTER
        }
        val rulePaint = Paint().apply { color = Color.BLACK; strokeWidth = 1.5f }

        val h = plan.result.header

        y += 20f
        canvas.drawText(plan.companyName.ifBlank { " " }, centerX, y, titlePaint)
        y += 18f
        val lotteryLine = listOfNotNull(
            h.lotteryName.ifBlank { null },
            h.drawNumber.ifBlank { null }?.let { "No. $it" },
            "DRAW"
        ).joinToString("  ")
        canvas.drawText(lotteryLine, centerX, y, subPaint)
        y += 14f
        val metaLine = buildString {
            append(listOfNotNull(h.drawDate.ifBlank { null }, h.drawTime.ifBlank { null }).joinToString("   "))
            if (h.venue.isNotBlank()) {
                if (isNotEmpty()) append("   ")
                append(h.venue)
            }
        }
        canvas.drawText(metaLine, centerX, y, metaPaint)
        y += 12f
        canvas.drawText(
            "Reprint of the official result — please verify against the Kerala Government Gazette.",
            centerX, y, disclaimerPaint
        )
        y += 8f
        canvas.drawLine(MARGIN, y, pageW - MARGIN, y, rulePaint)
        y += 12f

        val numberPaint = numberPaint(plan.numberFontSize)
        val labelPaint = labelPaint(plan.numberFontSize)
        val consolationPaint = Paint(numberPaint).apply { color = Color.rgb(150, 0, 0) }
        val leftX = MARGIN

        for (tr in plan.tierRows) {
            val tier = tr.tier
            val amountText = if (tier.amount.isNotBlank()) " — Rs ${tier.amount}" else ""
            canvas.drawText("${tier.label}$amountText", leftX, y, labelPaint)
            y += (plan.numberFontSize + 1f) * ROW_LEADING

            if (tr.isWinnerTier) {
                val bigPaint = Paint(numberPaint).apply {
                    textSize = plan.numberFontSize + 3f
                    color = if (tier.label.startsWith("1st")) Color.rgb(150, 0, 0) else Color.BLACK
                }
                if (tier.winners.isEmpty()) {
                    y += (plan.numberFontSize + 3f) * ROW_LEADING
                } else {
                    for (winner in tier.winners) {
                        canvas.drawText("${winner.ticketNumber}   (${winner.place})", leftX + 8f, y, bigPaint)
                        y += (plan.numberFontSize + 3f) * ROW_LEADING
                    }
                }
            } else {
                val paintForTier = if (tier.label.startsWith("Consolation")) consolationPaint else numberPaint
                val columns = tr.columns.coerceAtLeast(1)
                val colWidth = contentWidth / columns
                tier.numbers.forEachIndexed { index, num ->
                    val col = index % columns
                    if (col == 0 && index != 0) y += plan.numberFontSize * ROW_LEADING
                    val x = leftX + col * colWidth
                    canvas.drawText(num, x, y, paintForTier)
                }
                if (tier.numbers.isNotEmpty()) y += plan.numberFontSize * ROW_LEADING
            }
            y += TIER_GAP
        }
    }
}
