package com.eggbusinessmanager.domain.export

import com.eggbusinessmanager.domain.money.Money
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReportShareTextTest {

    private val statement = ReportDocument(
        title = "Customer statement",
        business = listOf("Patil Egg Centre", "Main Road, Nashik"),
        details = listOf("Customer: Ramesh Traders · 9876543210", "Period: 1 Nov 2026 – 30 Nov 2026"),
        summary = listOf(
            "Opening balance" to Cell.Amount(Money.rupees(1_000), "To receive ₹1,000.00"),
            "Closing balance" to Cell.Amount(Money.rupees(2_800), "To receive ₹2,800.00"),
        ),
        tables = listOf(Table(null, listOf(Column("Ref.")), listOf(listOf(Cell.Text("S-000001"))))),
        generatedAt = "Generated 30 Nov 2026, 18:05 · Egg Business Manager",
    )

    @Test
    fun `body has shop, report, customer, period and headline figures, then the closing line`() {
        assertThat(ReportShareText.body(statement, "PDF attached.")).isEqualTo(
            """
            Patil Egg Centre
            Customer statement
            Customer: Ramesh Traders · 9876543210
            Period: 1 Nov 2026 – 30 Nov 2026

            Opening balance: To receive ₹1,000.00
            Closing balance: To receive ₹2,800.00

            PDF attached.
            """.trimIndent(),
        )
    }

    @Test
    fun `body never includes table rows and copes with a bare document`() {
        assertThat(ReportShareText.body(statement, null)).doesNotContain("S-000001")
        val bare = statement.copy(business = emptyList(), details = emptyList(), summary = emptyList())
        assertThat(ReportShareText.body(bare, " ")).isEqualTo("Customer statement")
    }

    @Test
    fun `subject names report, customer and shop without labels`() {
        assertThat(ReportShareText.subject(statement))
            .isEqualTo("Customer statement – Ramesh Traders · 9876543210 – Patil Egg Centre")
        val receivables = statement.copy(title = "Outstanding receivables", details = listOf("As of 30 Nov 2026"))
        assertThat(ReportShareText.subject(receivables)).isEqualTo("Outstanding receivables – As of 30 Nov 2026 – Patil Egg Centre")
        assertThat(ReportShareText.subject(statement.copy(details = emptyList(), business = emptyList()))).isEqualTo("Customer statement")
    }
}
