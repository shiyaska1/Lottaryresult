package com.keralalottery.print.gold

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val UP_COLOR = Color(0xFF1B8A3A)
private val DOWN_COLOR = Color(0xFFC62828)
private val LINE_COLOR = Color(0xFF7A0C2E)

/** A plain Canvas line chart — no charting library, just the rate points joined by a line. */
@Composable
fun GoldChart(entries: List<GoldRateEntry>, modifier: Modifier = Modifier.fillMaxWidth().height(200.dp)) {
    if (entries.size < 2) {
        Text(
            "Not enough history yet for this period — check back after a few more days.",
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    val minRate = entries.minOf { it.ratePerGram }
    val maxRate = entries.maxOf { it.ratePerGram }
    val range = (maxRate - minRate).coerceAtLeast(1)
    val trendUp = entries.last().ratePerGram >= entries.first().ratePerGram

    Canvas(modifier = modifier) {
        val paddingLeft = 8.dp.toPx()
        val paddingRight = 8.dp.toPx()
        val paddingTop = 24.dp.toPx()
        val paddingBottom = 8.dp.toPx()
        val w = size.width - paddingLeft - paddingRight
        val h = size.height - paddingTop - paddingBottom
        val stepX = if (entries.size > 1) w / (entries.size - 1) else 0f

        fun pointFor(index: Int): Offset {
            val entry = entries[index]
            val x = paddingLeft + stepX * index
            val normalized = (entry.ratePerGram - minRate).toFloat() / range
            val y = paddingTop + h - (normalized * h)
            return Offset(x, y)
        }

        val path = androidx.compose.ui.graphics.Path()
        entries.indices.forEach { i ->
            val p = pointFor(i)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, color = if (trendUp) UP_COLOR else DOWN_COLOR, style = Stroke(width = 4.dp.toPx()))

        // Endpoint dot + top/bottom rate labels so the chart is readable without a legend.
        val last = pointFor(entries.lastIndex)
        drawCircle(color = LINE_COLOR, radius = 5.dp.toPx(), center = last)

        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.DKGRAY
                textSize = 11.sp.toPx()
                isAntiAlias = true
            }
            drawText("₹$maxRate", paddingLeft, paddingTop - 6.dp.toPx(), paint)
            drawText("₹$minRate", paddingLeft, size.height - 2.dp.toPx(), paint)
        }
    }
}
