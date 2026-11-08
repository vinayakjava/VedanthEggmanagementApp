package com.eggbusinessmanager.app.reports

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.app.feature.reports.ReportSharer
import com.eggbusinessmanager.data.export.FileReportExporter
import com.eggbusinessmanager.domain.export.Cell
import com.eggbusinessmanager.domain.export.Column
import com.eggbusinessmanager.domain.export.ReportDocument
import com.eggbusinessmanager.domain.export.ReportShareText
import com.eggbusinessmanager.domain.export.Table
import com.eggbusinessmanager.domain.logging.AppLogger
import com.eggbusinessmanager.domain.money.Money
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sharing through the Sharesheet (Phase 8): the PDF is served by the app's private FileProvider as a
 * content URI that readers can open, name and size, and the share intent carries exactly that URI with
 * a temporary read grant, the readable summary and a subject.
 */
@RunWith(AndroidJUnit4::class)
class ReportShareTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val logger = object : AppLogger {
        override fun debug(tag: String, message: () -> String) = Unit
        override fun info(tag: String, message: () -> String) = Unit
        override fun warn(tag: String, throwable: Throwable?, message: () -> String) = Unit
        override fun error(tag: String, throwable: Throwable?, message: () -> String) = Unit
    }
    private val sharer = ReportSharer(context, FileReportExporter(), logger)
    private val exports = File(context.cacheDir, "exports")

    private val document = ReportDocument(
        title = "Customer statement",
        business = listOf("Patil Egg Centre", "Main Road, Nashik"),
        details = listOf("Customer: Ramesh Traders · 9876543210", "Period: 1 Nov 2026 – 30 Nov 2026"),
        summary = listOf("Closing balance" to Cell.Amount(Money.rupees(2_800), "To receive ₹2,800.00")),
        tables = listOf(
            Table(null, listOf(Column("Ref."), Column("Amount")), listOf(listOf(Cell.Text("S-000001"), Cell.Text("₹1,800.00")))),
        ),
        generatedAt = "Generated 30 Nov 2026, 18:05",
    )

    @Before
    fun clean() {
        exports.deleteRecursively()
    }

    private fun prepared(name: String = "Customer-statement_Ramesh-Traders.pdf"): Uri =
        checkNotNull(runBlocking { sharer.preparePdf(document, name) })

    @Test
    fun pdfIsServedAsAContentUriReadersCanOpenNameAndSize() {
        val uri = prepared()
        assertThat(uri.scheme).isEqualTo("content")
        assertThat(uri.authority).isEqualTo("${context.packageName}.fileprovider")
        val resolver = context.contentResolver
        assertThat(resolver.getType(uri)).isEqualTo("application/pdf")
        val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
        assertThat(bytes.copyOfRange(0, 5).decodeToString()).isEqualTo("%PDF-")
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Customer-statement_Ramesh-Traders.pdf")
            assertThat(c.getLong(1)).isEqualTo(bytes.size.toLong())
        }
    }

    @Test
    fun shareIntentCarriesThePdfWithATemporaryReadGrantAndTheSummary() {
        val uri = prepared()
        val subject = ReportShareText.subject(document)
        val text = ReportShareText.body(document, "The full report is attached as a PDF.")
        val chooser = sharer.pdfChooser(uri, subject, text, "Share report")

        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("application/pdf")
        @Suppress("DEPRECATION")
        assertThat(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)).isEqualTo(uri)
        assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0) // read only
        assertThat(send.clipData!!.getItemAt(0).uri).isEqualTo(uri)
        assertThat(send.getStringExtra(Intent.EXTRA_SUBJECT))
            .isEqualTo("Customer statement – Ramesh Traders · 9876543210 – Patil Egg Centre")
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).contains("Closing balance: To receive ₹2,800.00")
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).doesNotContain("S-000001")
        // The Sharesheet passes the grant on to whichever app the owner picks.
        assertThat(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(chooser.clipData!!.getItemAt(0).uri).isEqualTo(uri)
    }

    @Test
    fun textShareHasNoAttachment() {
        val chooser = sharer.textChooser("Subject", "Body", "Share report")
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(send.type).isEqualTo("text/plain")
        assertThat(send.hasExtra(Intent.EXTRA_STREAM)).isFalse()
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("Body")
    }

    @Test
    fun sharingAppsCanBeFoundForPdfs() {
        // At least one app on the device accepts a shared PDF (on phones: WhatsApp, Gmail, Bluetooth, Quick Share, Drive…).
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
        val handlers = context.packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)
        assertThat(handlers).isNotEmpty()
    }

    @Test
    fun theProviderIsPrivateAndExposesOnlyTheExportFolder() {
        // The Int-flags overload works on every supported API level (31+).
        @Suppress("DEPRECATION")
        val info = context.packageManager.getProviderInfo(ComponentName(context, FileProvider::class.java), 0)
        assertThat(info.exported).isFalse()
        assertThat(info.grantUriPermissions).isTrue()
        // Files outside cache/exports (the database, keys, settings) can never be turned into a share URI.
        val database = context.getDatabasePath("egg_business.db")
        assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, sharer.authority, database) }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, sharer.authority, File(context.filesDir, "secret.txt"))
        }
    }

    @Test
    fun exportsOlderThanADayAreCleanedUpWhileRecentOnesStay() {
        exports.mkdirs()
        val stale = File(exports, "old.pdf").apply { writeText("x") }
        val recent = File(exports, "recent.pdf").apply { writeText("x") }
        val now = System.currentTimeMillis()
        stale.setLastModified(now - ReportSharer.MAX_AGE_MS - 60_000)
        recent.setLastModified(now - 60_000)
        prepared("new.pdf")
        assertThat(stale.exists()).isFalse()
        assertThat(recent.exists()).isTrue()
        assertThat(File(exports, "new.pdf").exists()).isTrue()
    }
}
