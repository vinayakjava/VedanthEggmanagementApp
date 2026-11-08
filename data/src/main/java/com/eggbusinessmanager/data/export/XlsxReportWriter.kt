package com.eggbusinessmanager.data.export

import com.eggbusinessmanager.domain.export.Align
import com.eggbusinessmanager.domain.export.Cell
import com.eggbusinessmanager.domain.export.ReportDocument
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A minimal, standards-compliant Office Open XML workbook (one sheet), written directly so the app does
 * not ship a spreadsheet library. Amounts are numbers formatted `#,##0.00`, dates are real Excel dates,
 * counts are integers, and text uses inline strings. Opens in Excel, Google Sheets and LibreOffice.
 */
object XlsxReportWriter {

    /** Cell style indexes into `cellXfs` in [STYLES]. */
    internal object Style {
        const val DEFAULT = 0
        const val BOLD = 1
        const val TITLE = 2
        const val HEADER = 3
        const val MONEY = 4
        const val DATE = 5
        const val INTEGER = 6
        const val FOOTER = 7
        const val FOOTER_MONEY = 8
        const val FOOTER_INTEGER = 9
        const val HEADER_END = 10
        const val MUTED = 11
    }

    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    private const val MAX_SHEET_NAME = 31
    private const val MIN_COLUMNS = 2
    private const val CHARS_PER_WEIGHT = 11.0
    private const val MIN_WIDTH = 10.0
    private const val LETTERS = 26

    fun write(document: ReportDocument, out: OutputStream) {
        val zip = ZipOutputStream(out)
        zip.entry("[Content_Types].xml", CONTENT_TYPES)
        zip.entry("_rels/.rels", ROOT_RELS)
        zip.entry("xl/workbook.xml", workbook(sheetName(document.title)))
        zip.entry("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
        zip.entry("xl/styles.xml", STYLES)
        zip.entry("xl/worksheets/sheet1.xml", sheet(document))
        zip.finish() // leaves [out] open for the caller
        out.flush()
    }

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    internal fun sheetName(title: String): String =
        title.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().take(MAX_SHEET_NAME).trim().ifEmpty { "Report" }

    private class SheetBuilder {
        val rows = StringBuilder()
        var row = 0

        fun add(cells: List<Pair<Cell, Int>>) {
            row++
            rows.append("<row r=\"").append(row).append("\">")
            cells.forEachIndexed { index, (cell, style) -> rows.append(cellXml(ref(index, row), cell, style)) }
            rows.append("</row>")
        }

        fun blank() {
            row++
        }
    }

    /** Title, letterhead, details and the summary figures. */
    private fun SheetBuilder.header(document: ReportDocument) {
        add(listOf(Cell.Text(document.title) to Style.TITLE))
        document.business.forEachIndexed { i, line -> add(listOf(Cell.Text(line) to if (i == 0) Style.BOLD else Style.DEFAULT)) }
        document.details.forEach { add(listOf(Cell.Text(it) to Style.DEFAULT)) }
        add(listOf(Cell.Text(document.generatedAt) to Style.MUTED))
        if (document.summary.isNotEmpty()) {
            blank()
            document.summary.forEach { (label, value) ->
                add(listOf(Cell.Text(label) to Style.BOLD, value to valueStyle(value, footer = false)))
            }
        }
    }

    private fun sheet(document: ReportDocument): String {
        val columnCount = maxOf(MIN_COLUMNS, document.tables.maxOfOrNull { it.columns.size } ?: 0)
        val b = SheetBuilder()
        b.header(document)
        document.tables.forEach { table ->
            b.blank()
            table.heading?.let { b.add(listOf(Cell.Text(it) to Style.BOLD)) }
            b.add(table.columns.map { Cell.Text(it.title) to if (it.align == Align.END) Style.HEADER_END else Style.HEADER })
            if (table.rows.isEmpty()) table.emptyText?.let { b.add(listOf(Cell.Text(it) to Style.MUTED)) }
            table.rows.forEach { cells -> b.add(cells.map { it to valueStyle(it, footer = false) }) }
            table.footer?.let { cells -> b.add(cells.map { it to valueStyle(it, footer = true) }) }
        }
        document.footnote?.let {
            b.blank()
            b.add(listOf(Cell.Text(it) to Style.MUTED))
        }
        val widths = (0 until columnCount).map { index ->
            val weight = document.tables.mapNotNull { it.columns.getOrNull(index)?.weight }.maxOrNull() ?: 1f
            maxOf(MIN_WIDTH, weight * CHARS_PER_WEIGHT)
        }
        val cols = widths.mapIndexed { i, w ->
            val width = "%.1f".format(java.util.Locale.ROOT, w)
            "<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$width\" customWidth=\"1\"/>"
        }.joinToString("")
        val lastRef = ref(columnCount - 1, maxOf(1, b.row))
        return XML_HEADER +
            "<worksheet xmlns=\"$MAIN_NS\" xmlns:r=\"$REL_NS\">" +
            "<dimension ref=\"A1:$lastRef\"/>" +
            "<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>" +
            "<sheetFormatPr defaultRowHeight=\"15\"/>" +
            "<cols>$cols</cols>" +
            "<sheetData>${b.rows}</sheetData>" +
            "<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>" +
            "<pageSetup paperSize=\"9\" orientation=\"portrait\" fitToWidth=\"1\" fitToHeight=\"0\"/>" +
            "</worksheet>"
    }

    private fun valueStyle(cell: Cell, footer: Boolean): Int = when (cell) {
        is Cell.Amount -> if (footer) Style.FOOTER_MONEY else Style.MONEY
        is Cell.Count -> if (footer) Style.FOOTER_INTEGER else Style.INTEGER
        is Cell.Day -> Style.DATE
        is Cell.Text -> if (footer) Style.FOOTER else Style.DEFAULT
    }

    private fun cellXml(ref: String, cell: Cell, style: Int): String = when (cell) {
        is Cell.Amount -> "<c r=\"$ref\" s=\"$style\"><v>${CsvReportWriter.plain(cell.money)}</v></c>"
        is Cell.Count -> "<c r=\"$ref\" s=\"$style\"><v>${cell.value}</v></c>"
        is Cell.Day -> "<c r=\"$ref\" s=\"$style\"><v>${cell.date.toEpochDay() - EXCEL_EPOCH.toEpochDay()}</v></c>"
        is Cell.Text -> if (cell.display.isEmpty() && style == Style.DEFAULT) {
            ""
        } else {
            "<c r=\"$ref\" t=\"inlineStr\" s=\"$style\"><is><t xml:space=\"preserve\">${escape(cell.display)}</t></is></c>"
        }
    }

    /** A1-style reference for a zero-based column and one-based row. */
    internal fun ref(column: Int, row: Int): String {
        var n = column + 1
        val letters = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % LETTERS
            letters.insert(0, 'A' + rem)
            n = (n - 1) / LETTERS
        }
        return "$letters$row"
    }

    /** XML text escaping; characters XML 1.0 cannot carry are dropped. */
    internal fun escape(text: String): String = buildString {
        text.forEach { ch ->
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' || ch >= ' ' -> if (ch != '￾' && ch != '￿') append(ch)
                else -> Unit
            }
        }
    }

    private const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun workbook(sheetName: String) = XML_HEADER +
        "<workbook xmlns=\"$MAIN_NS\" xmlns:r=\"$REL_NS\"><sheets>" +
        "<sheet name=\"${escape(sheetName)}\" sheetId=\"1\" r:id=\"rId1\"/>" +
        "</sheets></workbook>"

    private const val CONTENT_TYPES = XML_HEADER +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\"" +
        " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\"" +
        " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    private const val ROOT_RELS = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\"" +
        " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private const val WORKBOOK_RELS = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\"" +
        " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\"" +
        " Target=\"styles.xml\"/>" +
        "</Relationships>"

    private const val STYLES = XML_HEADER +
        "<styleSheet xmlns=\"$MAIN_NS\">" +
        "<numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.00\"/><numFmt numFmtId=\"165\"" +
        " formatCode=\"dd-mmm-yyyy\"/></numFmts>" +
        "<fonts count=\"4\">" +
        "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
        "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
        "<font><b/><sz val=\"14\"/><name val=\"Calibri\"/></font>" +
        "<font><i/><sz val=\"10\"/><color rgb=\"FF666666\"/><name val=\"Calibri\"/></font>" +
        "</fonts>" +
        "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE8EEF7\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
        "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>" +
        "<border><left/><right/><top style=\"thin\"><color auto=\"1\"/></top><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"12\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>" +
        "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\"/>" +
        "<xf numFmtId=\"164\" fontId=\"1\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\"" +
        " applyFont=\"1\" applyBorder=\"1\"/>" +
        "<xf numFmtId=\"3\" fontId=\"1\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"" +
        " applyBorder=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyAlignment=\"1\">" +
        "<alignment horizontal=\"right\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
        "</cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
        "</styleSheet>"
}
