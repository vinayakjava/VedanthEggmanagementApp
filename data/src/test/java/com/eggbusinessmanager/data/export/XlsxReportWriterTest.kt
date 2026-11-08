package com.eggbusinessmanager.data.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class XlsxReportWriterTest {

    private fun parts(rows: Int = 3): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        XlsxReportWriter.write(SampleDocuments.statement(rows), out)
        val zip = ZipInputStream(ByteArrayInputStream(out.toByteArray()))
        return generateSequence { zip.nextEntry }.associate { it.name to zip.readBytes() }
    }

    private fun xml(bytes: ByteArray): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private data class XCell(val ref: String, val type: String?, val style: String?, val value: String?, val text: String?)

    private fun cells(doc: Document): Map<String, XCell> {
        val list = doc.getElementsByTagNameNS("*", "c")
        return (0 until list.length).map { i ->
            val c = list.item(i) as Element
            XCell(
                ref = c.getAttribute("r"),
                type = c.getAttribute("t").ifEmpty { null },
                style = c.getAttribute("s").ifEmpty { null },
                value = c.getElementsByTagNameNS("*", "v").item(0)?.textContent,
                text = c.getElementsByTagNameNS("*", "t").item(0)?.textContent,
            )
        }.associateBy { it.ref }
    }

    @Test
    fun `is a complete OOXML package whose every part is well-formed XML`() {
        val parts = parts()
        assertThat(parts.keys).containsExactly(
            "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
            "xl/styles.xml", "xl/worksheets/sheet1.xml",
        )
        parts.values.forEach { xml(it) } // throws if any part is malformed
        val workbook = xml(parts.getValue("xl/workbook.xml"))
        assertThat((workbook.getElementsByTagNameNS("*", "sheet").item(0) as Element).getAttribute("name")).isEqualTo("Customer statement")
        val styles = xml(parts.getValue("xl/styles.xml"))
        val xfs = styles.getElementsByTagNameNS("*", "cellXfs").item(0) as Element
        assertThat(xfs.getAttribute("count").toInt()).isEqualTo(xfs.getElementsByTagNameNS("*", "xf").length)
    }

    @Test
    fun `amounts, dates and counts are typed cells with number formats`() {
        val sheet = xml(parts().getValue("xl/worksheets/sheet1.xml"))
        val cells = cells(sheet).values
        val firstDate = cells.first { it.style == XlsxReportWriter.Style.DATE.toString() }
        assertThat(firstDate.type).isNull()
        assertThat(firstDate.value).isEqualTo("46328") // 2 Nov 2026 as an Excel serial date
        val money = cells.filter { it.style == XlsxReportWriter.Style.MONEY.toString() }.map { it.value }
        assertThat(money).containsAtLeast("-250.50", "5400.00", "1800.00")
        assertThat(cells.single { it.style == XlsxReportWriter.Style.INTEGER.toString() && it.value == "12345" }.type).isNull()
        val footerMoney = cells.filter { it.style == XlsxReportWriter.Style.FOOTER_MONEY.toString() }.map { it.value }
        assertThat(footerMoney).containsExactly("5400.00", "0.00")
    }

    @Test
    fun `text is escaped and kept exactly, including Hindi and line breaks`() {
        val cells = cells(xml(parts().getValue("xl/worksheets/sheet1.xml"))).values
        val texts = cells.filter { it.type == "inlineStr" }.map { it.text }
        assertThat(texts).containsAtLeast(
            "Customer statement", "Patil Egg Centre", "Sale, \"Brown\" eggs\n10 trays <A&B>", "सीता स्टोर्स",
            "Customer: =HYPERLINK(\"evil\")",
        )
        assertThat(cells.first { it.text == "Customer statement" }.style).isEqualTo(XlsxReportWriter.Style.TITLE.toString())
        assertThat(cells.first { it.text == "Debit" }.style).isEqualTo(XlsxReportWriter.Style.HEADER_END.toString())
        assertThat(cells.first { it.text == "Date" }.style).isEqualTo(XlsxReportWriter.Style.HEADER.toString())
        assertThat(XlsxReportWriter.escape("a\u0001b<&>\"")).isEqualTo("ab&lt;&amp;&gt;&quot;")
    }

    @Test
    fun `rows are numbered in order and the dimension covers them, also for long reports`() {
        val sheet = xml(parts(rows = 1_500).getValue("xl/worksheets/sheet1.xml"))
        val rowElements = sheet.getElementsByTagNameNS("*", "row")
        val numbers = (0 until rowElements.length).map { (rowElements.item(it) as Element).getAttribute("r").toInt() }
        assertThat(numbers).isInOrder()
        assertThat(numbers.toSet()).hasSize(numbers.size)
        val dimension = (sheet.getElementsByTagNameNS("*", "dimension").item(0) as Element).getAttribute("ref")
        assertThat(dimension).isEqualTo("A1:F${numbers.last()}")
        assertThat(cells(sheet).values.count { it.style == XlsxReportWriter.Style.DATE.toString() }).isEqualTo(1_500)
    }

    @Test
    fun `cell references and sheet names follow Excel rules`() {
        assertThat(XlsxReportWriter.ref(0, 1)).isEqualTo("A1")
        assertThat(XlsxReportWriter.ref(25, 9)).isEqualTo("Z9")
        assertThat(XlsxReportWriter.ref(26, 10)).isEqualTo("AA10")
        assertThat(XlsxReportWriter.ref(701, 2)).isEqualTo("ZZ2")
        assertThat(XlsxReportWriter.sheetName("Sales: 1/11 – 30/11 [draft]?")).isEqualTo("Sales  1 11 – 30 11  draft")
        assertThat(XlsxReportWriter.sheetName("A".repeat(40))).hasLength(31)
        assertThat(XlsxReportWriter.sheetName("::")).isEqualTo("Report")
    }
}
