package org.openmiles.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import org.openmiles.app.domain.ReportData
import java.io.OutputStream

/** Draws a [ReportData] as a US Letter PDF: header and summary first, then the drive table. */
object PdfReport {
    private const val PAGE_W = 612
    private const val PAGE_H = 792
    private const val MARGIN = 40f
    private const val ROW_H = 15f
    // Column widths in points; they add up to the page width inside the margins (532).
    private val WIDTHS = floatArrayOf(58f, 92f, 40f, 82f, 170f, 48f, 42f)

    fun write(data: ReportData, out: OutputStream) {
        val doc = PdfDocument()
        val title = Paint().apply { textSize = 18f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
        val bold = Paint().apply { textSize = 9f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
        val body = Paint().apply { textSize = 9f; isAntiAlias = true }
        val small = Paint().apply { textSize = 8f; isAntiAlias = true; color = 0xFF555555.toInt() }
        val line = Paint().apply { strokeWidth = 0.5f; color = 0xFFBBBBBB.toInt() }

        // Work out the pages first so each footer can say "Page 2 of 5".
        val firstPageRows = ((PAGE_H - MARGIN * 2 - 60 - data.subtitle.size * 12 - data.summary.size * 13 - 40) / ROW_H).toInt().coerceAtLeast(5)
        val otherPageRows = ((PAGE_H - MARGIN * 2 - 40) / ROW_H).toInt()
        val pages = mutableListOf<List<List<String>>>()
        var rest = data.rows
        pages += rest.take(firstPageRows)
        rest = rest.drop(firstPageRows)
        while (rest.isNotEmpty()) {
            pages += rest.take(otherPageRows)
            rest = rest.drop(otherPageRows)
        }

        try {
            pages.forEachIndexed { index, rows ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create())
                val c = page.canvas
                var y = MARGIN
                if (index == 0) {
                    y += 18f
                    c.drawText(data.title, MARGIN, y, title)
                    y += 6f
                    data.subtitle.forEach {
                        y += 12f
                        c.drawText(fit(it, PAGE_W - MARGIN * 2, small), MARGIN, y, small)
                    }
                    y += 16f
                    data.summary.forEach { (label, value) ->
                        y += 13f
                        c.drawText(label, MARGIN, y, bold)
                        c.drawText(fit(value, PAGE_W - MARGIN * 2 - 150f, body), MARGIN + 150f, y, body)
                    }
                    y += 14f
                }
                // Table header
                y += ROW_H
                drawRow(c, data.header, y, bold)
                c.drawLine(MARGIN, y + 4f, PAGE_W - MARGIN, y + 4f, line)
                rows.forEach { row ->
                    y += ROW_H
                    drawRow(c, row, y, body)
                }
                if (data.rows.isEmpty() && index == 0) {
                    y += ROW_H
                    c.drawText("No drives in these dates.", MARGIN, y, body)
                }
                c.drawText("Page ${index + 1} of ${pages.size}", PAGE_W - MARGIN - 60f, PAGE_H - MARGIN / 2, small)
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun drawRow(c: android.graphics.Canvas, cells: List<String>, y: Float, paint: Paint) {
        var x = MARGIN
        cells.forEachIndexed { i, text ->
            val w = WIDTHS.getOrElse(i) { 50f }
            c.drawText(fit(text, w - 4f, paint), x, y, paint)
            x += w
        }
    }

    /** Shortens text with "..." so it never runs into the next column. */
    private fun fit(text: String, width: Float, paint: Paint): String {
        if (paint.measureText(text) <= width) return text
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText("...") > width) end--
        return text.substring(0, end) + "..."
    }
}
