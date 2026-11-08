package com.eggbusinessmanager.data.export

import com.eggbusinessmanager.domain.export.Cell
import com.eggbusinessmanager.domain.export.ReportDocument
import com.eggbusinessmanager.domain.money.Money
import java.io.OutputStream
import java.math.BigDecimal

/**
 * RFC 4180 CSV in UTF-8 with a byte-order mark, so Excel shows ₹ and Hindi correctly. Amounts are plain
 * decimals (1800.50, -250.00) and dates ISO (2026-11-08), so spreadsheets read them as numbers and dates.
 * Text that a spreadsheet would run as a formula (=, +, -, @, tab, CR at the start) is prefixed with an
 * apostrophe: customer names and remarks are typed by people and must never execute.
 */
object CsvReportWriter {

    private const val BOM = '\uFEFF'
    private const val EOL = "\r\n"
    private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')
    private val NEEDS_QUOTES = setOf(',', '"', '\n', '\r')

    fun write(document: ReportDocument, out: OutputStream) {
        val text = buildString {
            append(BOM)
            line(document.title)
            document.business.forEach { line(it) }
            document.details.forEach { line(it) }
            line(document.generatedAt)
            if (document.summary.isNotEmpty()) {
                append(EOL)
                document.summary.forEach { (label, cell) -> row(listOf(text(label), raw(cell))) }
            }
            document.tables.forEach { table ->
                append(EOL)
                table.heading?.let { line(it) }
                row(table.columns.map { text(it.title) })
                if (table.rows.isEmpty()) table.emptyText?.let { line(it) }
                table.rows.forEach { cells -> row(cells.map(::raw)) }
                table.footer?.let { cells -> row(cells.map(::raw)) }
            }
            document.footnote?.let {
                append(EOL)
                line(it)
            }
        }
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun StringBuilder.line(value: String) = row(listOf(text(value)))

    private fun StringBuilder.row(fields: List<String>) {
        append(fields.joinToString(",", transform = ::quote))
        append(EOL)
    }

    /** The value as a spreadsheet should read it. */
    internal fun raw(cell: Cell): String = when (cell) {
        is Cell.Text -> text(cell.display)
        is Cell.Amount -> plain(cell.money)
        is Cell.Count -> cell.value.toString()
        is Cell.Day -> cell.date.toString()
    }

    internal fun plain(money: Money): String = BigDecimal.valueOf(money.paise, 2).toPlainString()

    /** Neutralises formula-like text (CSV injection). */
    internal fun text(value: String): String = if (value.isNotEmpty() && value[0] in FORMULA_START) "'$value" else value

    internal fun quote(field: String): String =
        if (field.any { it in NEEDS_QUOTES }) "\"" + field.replace("\"", "\"\"") + "\"" else field
}
