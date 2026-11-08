// Page layout is expressed in typographic points (font sizes, gaps, column widths); naming every one
// of them would hide the layout rather than explain it.
@file:Suppress("MagicNumber")

package com.eggbusinessmanager.data.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.eggbusinessmanager.domain.export.Align
import com.eggbusinessmanager.domain.export.Cell
import com.eggbusinessmanager.domain.export.ReportDocument
import com.eggbusinessmanager.domain.export.Table
import java.io.OutputStream

/**
 * A4 PDF with Android's [PdfDocument]: shop letterhead, title and details, a shaded summary box, then
 * tables with a shaded header (repeated on every page), zebra rows, right-aligned figures and a bold
 * totals row. Every page carries "generated at" and "Page x of y". Layout runs twice: first to count
 * pages, then to draw, so the page total is known. System fonts render Hindi (Devanagari) as well.
 */
object PdfReportWriter {

    private const val PAGE_WIDTH = 595 // A4 in points
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
    private const val FOOTER_SPACE = 30f
    private const val ROW_HEIGHT = 17f
    private const val LINE_HEIGHT = 10.5f
    private const val MAX_LINES = 3
    private const val CELL_PADDING = 4f

    private val INK = Color.rgb(0x1F, 0x24, 0x2B)
    private val MUTED = Color.rgb(0x66, 0x6E, 0x78)
    private val ACCENT = Color.rgb(0x8A, 0x4B, 0x08)
    private val HEADER_FILL = Color.rgb(0xE8, 0xEE, 0xF7)
    private val STRIPE_FILL = Color.rgb(0xF6, 0xF7, 0xF9)
    private val SUMMARY_FILL = Color.rgb(0xFB, 0xF5, 0xEA)
    private val RULE = Color.rgb(0xC9, 0xCF, 0xD6)

    private fun paint(size: Float, bold: Boolean = false, color: Int = INK, italic: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        typeface = Typeface.create(Typeface.SANS_SERIF, style)
    }

    private val shopPaint = paint(17f, bold = true, color = ACCENT)
    private val addressPaint = paint(9f, color = MUTED)
    private val titlePaint = paint(15f, bold = true)
    private val detailPaint = paint(10f)
    private val labelPaint = paint(10f, color = MUTED)
    private val valuePaint = paint(11f, bold = true)
    private val headingPaint = paint(11.5f, bold = true)
    private val headPaint = paint(8.5f, bold = true)
    private val cellPaint = paint(8.5f)
    private val footPaint = paint(8.5f, bold = true)
    private val notePaint = paint(8.5f, color = MUTED, italic = true)
    private val pageFooterPaint = paint(7.5f, color = MUTED)
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val rulePaint = Paint().apply {
        color = RULE
        strokeWidth = 0.6f
    }

    fun write(document: ReportDocument, out: OutputStream) {
        val pages = Layout(document, pdf = null, totalPages = 0).run()
        val pdf = PdfDocument()
        try {
            Layout(document, pdf, pages).run()
            pdf.writeTo(out)
            out.flush()
        } finally {
            pdf.close()
        }
    }

    /** One pass over the document. With [pdf] null it only measures and returns the page count. */
    private class Layout(private val doc: ReportDocument, private val pdf: PdfDocument?, private val totalPages: Int) {
        private var page: PdfDocument.Page? = null
        private var pageNumber = 0
        private var y = 0f
        private val canvas: Canvas? get() = page?.canvas
        private val bottom = PAGE_HEIGHT - MARGIN - FOOTER_SPACE

        fun run(): Int {
            newPage(first = true)
            letterhead()
            summary()
            doc.tables.forEach(::table)
            doc.footnote?.let { note ->
                ensure(ROW_HEIGHT * 2)
                y += ROW_HEIGHT
                text(note, MARGIN, y, notePaint, CONTENT_WIDTH)
            }
            finishPage()
            return pageNumber
        }

        private fun newPage(first: Boolean = false) {
            finishPage()
            pageNumber++
            page = pdf?.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            y = MARGIN
            if (!first) {
                // Running header on later pages.
                y += 10f
                text("${doc.business.firstOrNull().orEmpty()} · ${doc.title}", MARGIN, y, addressPaint, CONTENT_WIDTH)
                y += 6f
                canvas?.drawLine(MARGIN, y, MARGIN + CONTENT_WIDTH, y, rulePaint)
                y += 14f
            }
        }

        private fun finishPage() {
            val current = page ?: return
            val footer = "${doc.generatedAt}  ·  Page $pageNumber of $totalPages"
            current.canvas.drawLine(MARGIN, PAGE_HEIGHT - MARGIN - 12f, MARGIN + CONTENT_WIDTH, PAGE_HEIGHT - MARGIN - 12f, rulePaint)
            val width = pageFooterPaint.measureText(footer)
            current.canvas.drawText(footer, (PAGE_WIDTH - width) / 2, PAGE_HEIGHT - MARGIN, pageFooterPaint)
            pdf?.finishPage(current)
            page = null
        }

        private fun ensure(height: Float) {
            if (y + height > bottom) newPage()
        }

        private fun letterhead() {
            doc.business.forEachIndexed { i, line ->
                val p = if (i == 0) shopPaint else addressPaint
                y += if (i == 0) 16f else 12f
                text(line, MARGIN, y, p, CONTENT_WIDTH)
            }
            y += 10f
            canvas?.drawLine(MARGIN, y, MARGIN + CONTENT_WIDTH, y, Paint(rulePaint).apply { color = ACCENT; strokeWidth = 1.2f })
            y += 24f
            text(doc.title, MARGIN, y, titlePaint, CONTENT_WIDTH)
            doc.details.forEach {
                y += 15f
                text(it, MARGIN, y, detailPaint, CONTENT_WIDTH)
            }
            y += 14f
            text(doc.generatedAt, MARGIN, y, addressPaint, CONTENT_WIDTH)
            y += 14f
        }

        /** Headline figures in a shaded box, two per row. */
        private fun summary() {
            if (doc.summary.isEmpty()) return
            val perRow = 2
            val rows = (doc.summary.size + perRow - 1) / perRow
            val boxHeight = rows * 34f + 12f
            ensure(boxHeight)
            canvas?.drawRect(MARGIN, y, MARGIN + CONTENT_WIDTH, y + boxHeight, fillPaint.apply { color = SUMMARY_FILL })
            val cellWidth = CONTENT_WIDTH / perRow
            doc.summary.forEachIndexed { i, (label, value) ->
                val x = MARGIN + 12f + (i % perRow) * cellWidth
                val top = y + 6f + (i / perRow) * 34f
                text(label, x, top + 12f, labelPaint, cellWidth - 24f)
                text(value.display, x, top + 27f, valuePaint, cellWidth - 24f)
            }
            y += boxHeight + 10f
        }

        private fun table(table: Table) {
            val totalWeight = table.columns.sumOf { it.weight.toDouble() }.toFloat()
            val widths = table.columns.map { CONTENT_WIDTH * it.weight / totalWeight }
            ensure(ROW_HEIGHT * 4)
            y += 12f
            table.heading?.let {
                y += 4f
                text(it, MARGIN, y, headingPaint, CONTENT_WIDTH)
                y += 8f
            }
            header(table, widths)
            if (table.rows.isEmpty()) {
                table.emptyText?.let {
                    y += ROW_HEIGHT
                    text(it, MARGIN + CELL_PADDING, y - 5f, notePaint, CONTENT_WIDTH)
                }
            }
            table.rows.forEachIndexed { index, cells ->
                val lines = wrapCells(cells, widths, cellPaint)
                val height = rowHeight(lines)
                if (y + height > bottom) {
                    newPage()
                    header(table, widths)
                }
                if (index % 2 == 1) {
                    canvas?.drawRect(MARGIN, y, MARGIN + CONTENT_WIDTH, y + height, fillPaint.apply { color = STRIPE_FILL })
                }
                row(table, widths, lines, cellPaint, height)
            }
            table.footer?.let { cells ->
                if (y + ROW_HEIGHT > bottom) {
                    newPage()
                    header(table, widths)
                }
                canvas?.drawLine(MARGIN, y, MARGIN + CONTENT_WIDTH, y, Paint(rulePaint).apply { color = INK; strokeWidth = 0.9f })
                val lines = wrapCells(cells, widths, footPaint)
                row(table, widths, lines, footPaint, rowHeight(lines))
            }
            y += 4f
        }

        private fun header(table: Table, widths: List<Float>) {
            canvas?.drawRect(MARGIN, y, MARGIN + CONTENT_WIDTH, y + ROW_HEIGHT + 2f, fillPaint.apply { color = HEADER_FILL })
            val titles = table.columns.mapIndexed { i, c -> listOf(fit(c.title, headPaint, widths[i] - 2 * CELL_PADDING)) }
            row(table, widths, titles, headPaint, height = ROW_HEIGHT + 2f)
        }

        /** Each cell as up to [MAX_LINES] lines that fit its column. */
        private fun wrapCells(cells: List<Cell>, widths: List<Float>, p: Paint): List<List<String>> =
            cells.mapIndexed { i, cell -> wrap(cell.display, p, widths[i] - 2 * CELL_PADDING) }

        private fun rowHeight(lines: List<List<String>>): Float =
            maxOf(ROW_HEIGHT, (lines.maxOf { it.size } - 1) * LINE_HEIGHT + ROW_HEIGHT)

        /** Draws pre-wrapped cells top-aligned; figures (END-aligned) sit on the first line, right-aligned. */
        private fun row(table: Table, widths: List<Float>, lines: List<List<String>>, p: Paint, height: Float) {
            var x = MARGIN
            lines.forEachIndexed { i, cellLines ->
                val w = widths[i]
                cellLines.forEachIndexed { n, shown ->
                    val tx = if (table.columns[i].align == Align.END) x + w - CELL_PADDING - p.measureText(shown) else x + CELL_PADDING
                    canvas?.drawText(shown, tx, y + ROW_HEIGHT - 5f + n * LINE_HEIGHT, p)
                }
                x += w
            }
            y += height
            canvas?.drawLine(MARGIN, y, MARGIN + CONTENT_WIDTH, y, rulePaint)
        }

        private fun text(value: String, x: Float, baseline: Float, p: Paint, maxWidth: Float) {
            canvas?.drawText(fit(value, p, maxWidth), x, baseline, p)
        }
    }

    /**
     * Word-wraps [value] into lines no wider than [width]; a word longer than a line is broken, and text
     * beyond [MAX_LINES] lines ends with an ellipsis.
     */
    internal fun wrap(value: String, p: Paint, width: Float): List<String> {
        if (value.isEmpty() || p.measureText(value) <= width) return listOf(value)
        val lines = mutableListOf<String>()
        var current = ""
        val words = value.split(' ').toMutableList()
        while (words.isNotEmpty()) {
            val word = words.removeAt(0)
            val candidate = if (current.isEmpty()) word else "$current $word"
            when {
                p.measureText(candidate) <= width -> current = candidate
                current.isNotEmpty() -> {
                    lines += current
                    current = ""
                    words.add(0, word)
                }
                else -> {
                    // A single word wider than the column: break it.
                    val count = maxOf(1, p.breakText(word, true, width, null))
                    lines += word.take(count)
                    words.add(0, word.drop(count))
                }
            }
            if (lines.size == MAX_LINES) {
                val rest = (listOf(current) + words).filter { it.isNotEmpty() }.joinToString(" ")
                if (rest.isNotEmpty()) lines[MAX_LINES - 1] = fit(lines[MAX_LINES - 1] + " " + rest, p, width)
                return lines
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    /** Shortens [value] with an ellipsis so it fits [width]. */
    internal fun fit(value: String, p: Paint, width: Float): String {
        if (p.measureText(value) <= width) return value
        val ellipsis = "…"
        val count = p.breakText(value, true, width - p.measureText(ellipsis), null)
        return value.take(maxOf(0, count)).trimEnd() + ellipsis
    }
}
