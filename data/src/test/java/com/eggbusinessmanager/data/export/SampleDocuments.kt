package com.eggbusinessmanager.data.export

import com.eggbusinessmanager.domain.export.Align
import com.eggbusinessmanager.domain.export.Cell
import com.eggbusinessmanager.domain.export.Column
import com.eggbusinessmanager.domain.export.ReportDocument
import com.eggbusinessmanager.domain.export.Table
import com.eggbusinessmanager.domain.money.Money
import java.time.LocalDate

/** Documents shared by the export tests. */
object SampleDocuments {

    fun money(paise: Long, display: String) = Cell.Amount(Money(paise), display)

    /** A customer statement with tricky text: commas, quotes, a newline, XML specials and formula-like names. */
    fun statement(rows: Int = 3): ReportDocument {
        val lines = (1..rows).map { i ->
            listOf(
                Cell.Day(LocalDate.of(2026, 11, 1).plusDays((i % 28).toLong()), "${i % 28 + 1} Nov 2026"),
                Cell.Text("S-${i.toString().padStart(6, '0')}"),
                Cell.Text(if (i == 1) "Sale, \"Brown\" eggs\n10 trays <A&B>" else "Sale $i"),
                money(180_000, "₹1,800.00"),
                Cell.EMPTY,
                money(180_000L * i, "₹${1_800 * i}.00"),
            )
        }
        return ReportDocument(
            title = "Customer statement",
            business = listOf("Patil Egg Centre", "Main Road, Nashik, Maharashtra 422001"),
            details = listOf("Customer: =HYPERLINK(\"evil\")", "1 Nov 2026 – 30 Nov 2026"),
            summary = listOf(
                "Opening balance" to money(-25_050, "To pay ₹250.50"),
                "Closing balance" to money(540_000, "To receive ₹5,400.00"),
            ),
            tables = listOf(
                Table(
                    heading = "Transactions",
                    columns = listOf(
                        Column("Date", 1.2f),
                        Column("Reference", 1.2f),
                        Column("Details", 3f),
                        Column("Debit", 1.3f, Align.END),
                        Column("Credit", 1.3f, Align.END),
                        Column("Balance", 1.5f, Align.END),
                    ),
                    rows = lines,
                    footer = listOf(Cell.Text("Total"), Cell.EMPTY, Cell.EMPTY, money(180_000L * rows, "₹"), money(0, "₹0.00"), Cell.EMPTY),
                ),
                Table(
                    heading = "By customer",
                    columns = listOf(Column("Customer", 3f), Column("Eggs", 1f, Align.END)),
                    rows = listOf(
                        listOf(Cell.Text("-Minus Traders"), Cell.Count(12_345, "12,345")),
                        listOf(Cell.Text("सीता स्टोर्स"), Cell.Count(60, "60")),
                    ),
                ),
                Table(
                    heading = "Empty",
                    columns = listOf(Column("A"), Column("B")),
                    rows = emptyList(),
                    emptyText = "Nothing in this period",
                ),
            ),
            generatedAt = "Generated 8 Nov 2026, 10:30",
            footnote = "Positive balance = customer owes you.",
        )
    }
}
