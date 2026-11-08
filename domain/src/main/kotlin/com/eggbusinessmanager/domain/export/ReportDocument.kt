package com.eggbusinessmanager.domain.export

import com.eggbusinessmanager.domain.money.Money
import java.io.OutputStream
import java.time.LocalDate

/**
 * A finished, language-specific report ready for any file format. Cells keep their typed value (so CSV
 * and Excel get real numbers and dates) next to the text shown on screen and in the PDF.
 */
data class ReportDocument(
    val title: String,
    /** Shop name first, then address lines. */
    val business: List<String>,
    /** Period, customer and similar lines under the title. */
    val details: List<String>,
    /** Headline figures, e.g. opening and closing balance. */
    val summary: List<Pair<String, Cell>>,
    val tables: List<Table>,
    val generatedAt: String,
    val footnote: String? = null,
)

enum class Align { START, END }

data class Column(val title: String, val weight: Float = 1f, val align: Align = Align.START)

data class Table(
    val heading: String?,
    val columns: List<Column>,
    val rows: List<List<Cell>>,
    val footer: List<Cell>? = null,
    /** Shown instead of rows when there are none. */
    val emptyText: String? = null,
) {
    init {
        require(columns.isNotEmpty()) { "A table needs columns" }
        require(rows.all { it.size == columns.size }) { "Every row needs one cell per column" }
        require(footer == null || footer.size == columns.size) { "The footer needs one cell per column" }
    }
}

/** A table cell: [display] is the formatted text; the typed variants carry the raw value for spreadsheets. */
sealed interface Cell {
    val display: String

    data class Text(override val display: String) : Cell
    data class Amount(val money: Money, override val display: String) : Cell
    data class Count(val value: Long, override val display: String) : Cell
    data class Day(val date: LocalDate, override val display: String) : Cell

    companion object {
        val EMPTY: Cell = Text("")
    }
}

enum class ExportFormat(val extension: String, val mimeType: String) {
    PDF("pdf", "application/pdf"),
    CSV("csv", "text/csv"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
}

/** Writes a [ReportDocument] in a file format. Does not close [out]. */
interface ReportExporter {
    fun export(document: ReportDocument, format: ExportFormat, out: OutputStream)
}

/**
 * A safe file name: letters with their combining marks (Hindi vowel signs), digits, dashes and
 * underscores only, with the format's extension.
 */
object ReportFileName {
    private val UNSAFE = Regex("[^\\p{L}\\p{M}\\p{N}_-]+")
    private const val MAX_LENGTH = 80

    fun of(parts: List<String>, format: ExportFormat): String {
        val base = parts.filter { it.isNotBlank() }
            .joinToString("_") { it.trim().replace(UNSAFE, "-").trim('-') }
            .take(MAX_LENGTH)
            .ifEmpty { "report" }
        return "$base.${format.extension}"
    }
}
