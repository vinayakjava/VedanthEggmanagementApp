package com.eggbusinessmanager.data.export

import com.eggbusinessmanager.domain.money.Money
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream

class CsvReportWriterTest {

    private fun csv(): String {
        val out = ByteArrayOutputStream()
        CsvReportWriter.write(SampleDocuments.statement(), out)
        return out.toString(Charsets.UTF_8.name())
    }

    /** Minimal RFC 4180 reader: quoted fields may hold commas, doubled quotes and line breaks. */
    private fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' -> quoted = true
                c == ',' -> { row.add(field.toString()); field.clear() }
                c == '\r' && text.getOrNull(i + 1) == '\n' -> {
                    row.add(field.toString())
                    field.clear()
                    rows.add(row)
                    row = mutableListOf()
                    i++
                }
                else -> field.append(c)
            }
            i++
        }
        return rows
    }

    @Test
    fun `starts with a UTF-8 byte order mark and uses CRLF line endings`() {
        val out = ByteArrayOutputStream()
        CsvReportWriter.write(SampleDocuments.statement(), out)
        val bytes = out.toByteArray()
        assertThat(bytes.take(3)).containsExactly(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()).inOrder()
        val text = csv().removePrefix("﻿")
        assertThat(text.replace("\r\n", "")).doesNotContain("\r")
        assertThat(text).startsWith("Customer statement\r\n")
    }

    @Test
    fun `round-trips through a CSV reader with header, typed values and totals`() {
        val rows = parse(csv().removePrefix("﻿"))
        assertThat(rows[0]).containsExactly("Customer statement")
        assertThat(rows).contains(listOf("Opening balance", "-250.50"))
        assertThat(rows).contains(listOf("Closing balance", "5400.00"))
        val header = rows.indexOf(listOf("Date", "Reference", "Details", "Debit", "Credit", "Balance"))
        assertThat(header).isGreaterThan(0)
        // Dates are ISO, amounts plain decimals, empty cells empty; the tricky text survives intact.
        assertThat(rows[header + 1])
            .containsExactly("2026-11-02", "S-000001", "Sale, \"Brown\" eggs\n10 trays <A&B>", "1800.00", "", "1800.00").inOrder()
        assertThat(rows[header + 4]).containsExactly("Total", "", "", "5400.00", "0.00", "").inOrder()
        assertThat(rows).contains(listOf("सीता स्टोर्स", "60"))
        assertThat(rows).contains(listOf("Nothing in this period"))
        assertThat(rows.last()).containsExactly("Positive balance = customer owes you.")
    }

    @Test
    fun `formula-like text is neutralised but negative amounts stay numbers`() {
        val rows = parse(csv().removePrefix("﻿"))
        // "=" inside the text is harmless; only a formula character at the start is escaped.
        assertThat(rows).contains(listOf("Customer: =HYPERLINK(\"evil\")"))
        assertThat(rows).contains(listOf("'-Minus Traders", "12345"))
        assertThat(CsvReportWriter.text("=1+1")).isEqualTo("'=1+1")
        assertThat(CsvReportWriter.text("+91 98765")).isEqualTo("'+91 98765")
        assertThat(CsvReportWriter.text("@SUM(A1)")).isEqualTo("'@SUM(A1)")
        assertThat(CsvReportWriter.text("Ramesh")).isEqualTo("Ramesh")
        assertThat(CsvReportWriter.plain(Money(-5))).isEqualTo("-0.05")
        assertThat(CsvReportWriter.plain(Money(123_456_789))).isEqualTo("1234567.89")
    }

    @Test
    fun `quoting follows RFC 4180`() {
        assertThat(CsvReportWriter.quote("plain")).isEqualTo("plain")
        assertThat(CsvReportWriter.quote("a,b")).isEqualTo("\"a,b\"")
        assertThat(CsvReportWriter.quote("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"")
        assertThat(CsvReportWriter.quote("two\nlines")).isEqualTo("\"two\nlines\"")
    }
}
