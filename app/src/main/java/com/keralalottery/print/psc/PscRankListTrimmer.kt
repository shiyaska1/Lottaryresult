package com.keralalottery.print.psc

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Trims a PSC rank-list PDF down to just its actual rank-table pages. Every KPSC rank list
 * follows the same template after the data: a near-blank page bearing just the official seal,
 * then several pages of boilerplate legal notes always starting "NOTE (1) :-". This drops both
 * - whole pages only, nothing on a kept page is touched - so it's safe regardless of how a given
 * post's community-quota subsections are laid out inside the table itself. If "NOTE (1)" isn't
 * found (a differently-formatted PDF), nothing is removed rather than risking cutting real data.
 */
object PscRankListTrimmer {

    private var loaderInitialized = false
    private const val NOTES_MARKER = "NOTE (1)"
    // Below this many non-whitespace characters, a page is treated as the blank seal page
    // rather than real content - real rank-table pages carry a full table of names/numbers.
    private const val MIN_DATA_CHARS = 80

    /** Downloads must happen separately; this only trims already-fetched PDF [bytes] and writes the result to [outFile]. Must run off the main thread. */
    fun trim(context: Context, bytes: ByteArray, outFile: File) {
        ensureLoader(context)
        PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
            val stripper = PDFTextStripper()
            fun textOf(page: Int): String {
                stripper.startPage = page
                stripper.endPage = page
                return stripper.getText(doc)
            }

            val totalPages = doc.numberOfPages
            val notesPage = (1..totalPages).firstOrNull { textOf(it).contains(NOTES_MARKER, ignoreCase = true) }
                ?: (totalPages + 1)

            var lastDataPage = notesPage - 1
            while (lastDataPage > 0 && textOf(lastDataPage).trim().length < MIN_DATA_CHARS) {
                lastDataPage--
            }

            if (lastDataPage in 0 until totalPages) {
                // Remove from the end backward so earlier (kept) page indices never shift.
                for (i in totalPages downTo lastDataPage + 1) {
                    doc.removePage(i - 1)
                }
            }
            if (doc.numberOfPages == 0) {
                error("Could not find any rank-table pages to keep - this PDF may be entirely boilerplate.")
            }
            doc.save(outFile)
        }
    }

    private fun ensureLoader(context: Context) {
        if (!loaderInitialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            loaderInitialized = true
        }
    }
}
