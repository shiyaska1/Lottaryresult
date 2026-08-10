package com.keralalottery.print.gold

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Renders today's rate as a portrait card over a jeweller-supplied background, ready to share
 * straight to a WhatsApp status (or any other app) via the normal Android share sheet. */
object GoldShareImage {

    private const val WIDTH = 1080
    private const val HEIGHT = 1920

    fun render(background: Bitmap, today: GoldRateEntry, previous: GoldRateEntry?): Bitmap {
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        drawCoverFitted(canvas, background)

        // Dark scrim panel behind the text so it stays readable over any photo.
        val panelTop = HEIGHT * 0.58f
        canvas.drawRect(RectF(0f, panelTop, WIDTH.toFloat(), HEIGHT.toFloat()), Paint().apply {
            color = Color.argb(190, 20, 10, 12)
        })

        val centerX = WIDTH / 2f
        var y = panelTop + 110f

        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 54f
            isFakeBoldText = true
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("22K GOLD RATE — KERALA", centerX, y, titlePaint)

        val datePaint = Paint(titlePaint).apply { textSize = 32f; isFakeBoldText = false; color = Color.rgb(240, 216, 220) }
        y += 56f
        canvas.drawText(today.date.toString(), centerX, y, datePaint)

        y += 100f
        val priceLabelPaint = Paint(titlePaint).apply { textSize = 34f; color = Color.rgb(240, 216, 220); isFakeBoldText = false }
        val pricePaint = Paint(titlePaint).apply { textSize = 76f }

        canvas.drawText("1 gram", centerX, y, priceLabelPaint)
        y += 82f
        canvas.drawText(rupees(today.ratePerGram), centerX, y, pricePaint)

        y += 90f
        canvas.drawText("8 gram (1 Pavan)", centerX, y, priceLabelPaint)
        y += 82f
        canvas.drawText(rupees(today.ratePerGram * 8), centerX, y, pricePaint)

        if (previous != null) {
            val deltaPerGram = today.ratePerGram - previous.ratePerGram
            val up = deltaPerGram >= 0
            val arrow = if (up) "▲" else "▼"
            val arrowColor = if (up) Color.rgb(76, 217, 100) else Color.rgb(255, 99, 99)
            val changePaint = Paint(titlePaint).apply { textSize = 40f; color = arrowColor }
            y += 100f
            canvas.drawText(
                "$arrow ${rupees(kotlin.math.abs(deltaPerGram))}/g   ·   $arrow ${rupees(kotlin.math.abs(deltaPerGram) * 8)}/pavan vs yesterday",
                centerX, y, changePaint
            )
        }

        return out
    }

    /** Scales+crops the background to fill the whole canvas (like CSS `background-size: cover`). */
    private fun drawCoverFitted(canvas: Canvas, background: Bitmap) {
        val targetRatio = WIDTH.toFloat() / HEIGHT.toFloat()
        val srcRatio = background.width.toFloat() / background.height.toFloat()
        val src = if (srcRatio > targetRatio) {
            val cropWidth = (background.height * targetRatio).toInt().coerceAtMost(background.width)
            val x = (background.width - cropWidth) / 2
            android.graphics.Rect(x, 0, x + cropWidth, background.height)
        } else {
            val cropHeight = (background.width / targetRatio).toInt().coerceAtMost(background.height)
            val y = (background.height - cropHeight) / 2
            android.graphics.Rect(0, y, background.width, y + cropHeight)
        }
        canvas.drawBitmap(background, src, RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private fun rupees(amount: Int): String {
        val symbols = DecimalFormatSymbols(Locale.ENGLISH)
        val format = DecimalFormat("##,##,##0", symbols)
        return "₹${format.format(amount)}"
    }

    fun shareToWhatsAppStatus(context: Context, bitmap: Bitmap) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "gold_rate_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share rate card"))
    }
}
