package com.eggbusinessmanager.app.reports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.app.feature.reports.Catalog
import com.eggbusinessmanager.app.feature.reports.ReportDocumentFactory
import com.eggbusinessmanager.app.feature.reports.ReportFileSaver
import com.eggbusinessmanager.data.export.FileReportExporter
import com.eggbusinessmanager.domain.catalog.EggVariety
import com.eggbusinessmanager.domain.catalog.MeasurementUnit
import com.eggbusinessmanager.domain.customer.Customer
import com.eggbusinessmanager.domain.export.ExportFormat
import com.eggbusinessmanager.domain.export.ReportDocument
import com.eggbusinessmanager.domain.ledger.BalanceEffect
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.ledger.OpeningDirection
import com.eggbusinessmanager.domain.ledger.PaymentLine
import com.eggbusinessmanager.domain.ledger.PaymentMode
import com.eggbusinessmanager.domain.ledger.TradeLine
import com.eggbusinessmanager.domain.logging.AppLogger
import com.eggbusinessmanager.domain.model.account.BusinessProfile
import com.eggbusinessmanager.domain.model.account.IndianState
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.money.QuantityMilli
import com.eggbusinessmanager.domain.report.ReportBuilder
import com.eggbusinessmanager.domain.report.ReportKind
import com.eggbusinessmanager.domain.report.ReportRequest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Export end to end on a device (Phase 7): a customer statement built by the real builder and factory is
 * saved through the same [ReportFileSaver] the Storage Access Framework uses, then each file is opened
 * the way a reader would — the PDF rendered page by page, the workbook unzipped and parsed, the CSV read.
 */
@RunWith(AndroidJUnit4::class)
class ReportExportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.cacheDir, "export-test").apply { mkdirs() }
    private val logger = object : AppLogger {
        override fun debug(tag: String, message: () -> String) = Unit
        override fun info(tag: String, message: () -> String) = Unit
        override fun warn(tag: String, throwable: Throwable?, message: () -> String) = Unit
        override fun error(tag: String, throwable: Throwable?, message: () -> String) = Unit
    }
    private val saver = ReportFileSaver(context, FileReportExporter(), logger)
    private val factory = ReportDocumentFactory(context)

    private val start = LocalDate.of(2026, 11, 1)
    private val customer = Customer(7, "Ramesh Traders", "9876543210", null, null, true, Instant.EPOCH, Instant.EPOCH)
    private val catalog = Catalog(
        units = mapOf(3L to MeasurementUnit(3, "Tray", 30, true, true)),
        varieties = mapOf(2L to EggVariety(2, "Brown", true, false)),
    )
    private val profile =
        BusinessProfile("Patil Egg Centre", "Sunil Patil", "Shop 4, Main Road", "Nashik", IndianState.MAHARASHTRA, "422001")
    private val november = ReportRequest(ReportKind.CUSTOMER_STATEMENT, start, start.plusDays(29), customer.id)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private var nextId = 1L

    private fun entry(type: EntryType, rupees: Long, date: LocalDate, direction: OpeningDirection? = null): LedgerEntry {
        val id = nextId++
        val amount = Money.rupees(rupees)
        val effect = if (direction != null) {
            if (direction == OpeningDirection.CUSTOMER_OWES) amount else -amount
        } else {
            BalanceEffect.of(type, amount)
        }
        val prefix = mapOf(EntryType.SELL to "S", EntryType.BUY to "B", EntryType.PAYMENT_RECEIVED to "RP", EntryType.PAYMENT_MADE to "PC")
        val reference = "${prefix[type] ?: "OB"}-${id.toString().padStart(6, '0')}"
        val isTrade = type == EntryType.SELL || type == EntryType.BUY
        val isPayment = type == EntryType.PAYMENT_RECEIVED || type == EntryType.PAYMENT_MADE
        return LedgerEntry(
            id, id, reference, type, EntryStatus.ACTIVE, customer.id, date, amount, effect,
            direction, if (id % 5 == 0L) "Remark, with \"quotes\"" else null, Instant.EPOCH.plusSeconds(id),
            trade = if (isTrade) TradeLine(2, 3, QuantityMilli(10_000), 30, 300, Money(amount.paise / 10)) else null,
            payment = if (isPayment) PaymentLine(PaymentMode.UPI, "UPI-$id") else null,
        )
    }

    /** Opening balance before the range, then [lines] alternating sales and receipts inside it. */
    private fun statement(lines: Int): ReportDocument {
        val entries = listOf(entry(EntryType.OPENING_BALANCE, 1_000, start.minusDays(20), OpeningDirection.CUSTOMER_OWES)) +
            (0 until lines).map { i ->
                val type = if (i % 2 == 0) EntryType.SELL else EntryType.PAYMENT_RECEIVED
                entry(type, 1_800L - (i % 2) * 800, start.plusDays((i % 28).toLong()))
            }
        val data = ReportBuilder.statement(november, customer, entries)
        return factory.build(data, profile, catalog, LocalDateTime.of(2026, 11, 30, 18, 5))
    }

    private fun save(document: ReportDocument, format: ExportFormat): File {
        val file = File(dir, "statement.${format.extension}")
        assertThat(runBlocking { saver.save(Uri.fromFile(file), document, format) }).isTrue()
        assertThat(file.length()).isGreaterThan(0L)
        return file
    }

    @Test
    fun pdfOpensAsAnA4DocumentWithInkOnEveryPageAndPaginatesLongStatements() {
        fun pages(file: File): List<Bitmap> = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
            (0 until pdf.pageCount).map { i ->
                pdf.openPage(i).use { page ->
                    assertThat(page.width).isEqualTo(595)
                    assertThat(page.height).isEqualTo(842)
                    Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
        fun inked(bitmap: Bitmap): Int {
            var dark = 0
            for (y in 0 until bitmap.height step 4) {
                for (x in 0 until bitmap.width step 4) {
                    if (Color.red(bitmap.getPixel(x, y)) < 128) dark++
                }
            }
            return dark
        }

        val short = save(statement(lines = 6), ExportFormat.PDF)
        assertThat(short.readBytes().take(5).toByteArray().decodeToString()).isEqualTo("%PDF-")
        val one = pages(short)
        assertThat(one).hasSize(1)
        assertThat(inked(one.single())).isGreaterThan(200)

        val long = pages(save(statement(lines = 140), ExportFormat.PDF))
        assertThat(long.size).isAtLeast(3)
        long.forEach { assertThat(inked(it)).isGreaterThan(100) }
    }

    @Test
    fun workbookIsAValidSpreadsheetWithNumbersForAmounts() {
        val file = save(statement(lines = 6), ExportFormat.XLSX)
        ZipFile(file).use { zip ->
            assertThat(zip.entries().toList().map { it.name })
                .containsAtLeast("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml")
            val sheet = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
                .parse(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")))
            val texts = sheet.getElementsByTagNameNS("*", "t").let { list -> (0 until list.length).map { list.item(it).textContent } }
            assertThat(texts).containsAtLeast("Patil Egg Centre", "Balance brought forward")
            val numbers = sheet.getElementsByTagNameNS("*", "v").let { list -> (0 until list.length).map { list.item(it).textContent } }
            assertThat(numbers).containsAtLeast("1000.00", "1800.00") // brought forward and the first sale, as numbers
        }
    }

    @Test
    fun csvCarriesTheStatementWithOpeningRunningAndClosingBalances() {
        val text = save(statement(lines = 4), ExportFormat.CSV).readText(Charsets.UTF_8)
        assertThat(text).startsWith("﻿")
        assertThat(text).contains("Balance brought forward")
        assertThat(text).contains("2026-11-01,") // ISO dates
        // 1,000 opening + 1,800 − 1,000 + 1,800 − 1,000 = 2,600 closing
        assertThat(text.lines().first { it.startsWith("Total") }).endsWith(",3600.00,2000.00,2600.00")
        // A remark with a comma and quotes stays inside one correctly quoted field.
        assertThat(text).contains("· Remark, with \"\"quotes\"\"\",")
    }

    @Test
    fun savingReportsFailureInsteadOfCrashingWhenTheTargetCannotBeOpened() {
        val missing = Uri.parse("content://com.eggbusinessmanager.not.a.provider/report.pdf")
        assertThat(runBlocking { saver.save(missing, statement(lines = 2), ExportFormat.PDF) }).isFalse()
    }

    @Test
    fun suggestedFileNamesNameTheReportCustomerAndPeriod() {
        val data = ReportBuilder.statement(november, customer, emptyList())
        assertThat(factory.fileName(data, ExportFormat.PDF)).isEqualTo("Customer-statement_Ramesh-Traders_2026-11-01_2026-11-30.pdf")
        assertThat(factory.fileName(data, ExportFormat.XLSX)).endsWith(".xlsx")
    }
}
