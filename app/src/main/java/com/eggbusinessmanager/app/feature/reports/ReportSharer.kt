package com.eggbusinessmanager.app.feature.reports

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.eggbusinessmanager.domain.export.ExportFormat
import com.eggbusinessmanager.domain.export.ReportDocument
import com.eggbusinessmanager.domain.export.ReportExporter
import com.eggbusinessmanager.domain.logging.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Shares reports through the Android Sharesheet (Phase 8): WhatsApp, Gmail, Bluetooth, Quick Share and
 * any other installed app that accepts the content. Nothing goes through a server of ours.
 *
 * The PDF is written to the app's private `cache/exports` folder and handed out as a `content://` URI
 * from the app's non-exported [FileProvider]; the receiving app gets temporary read access to that one
 * file through the intent's grant flag and ClipData, and to nothing else. Old export files are deleted.
 */
class ReportSharer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exporter: ReportExporter,
    private val logger: AppLogger,
) {
    private val exportDir: File get() = File(context.cacheDir, EXPORT_DIR)
    val authority: String get() = "${context.packageName}.fileprovider"

    /** Writes [document] as a PDF named [fileName] and returns its shareable URI, or null if writing failed. */
    suspend fun preparePdf(document: ReportDocument, fileName: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val dir = exportDir.apply { mkdirs() }
            deleteOld(dir, System.currentTimeMillis())
            val file = File(dir, fileName)
            file.outputStream().use { exporter.export(document, ExportFormat.PDF, it) }
            FileProvider.getUriForFile(context, authority, file)
        } catch (e: IOException) {
            logger.warn(TAG, e) { "Could not prepare the PDF for sharing" }
            null
        }
    }

    /** The Sharesheet for a PDF with a readable message alongside it. */
    fun pdfChooser(uri: Uri, subject: String, text: String, chooserTitle: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = ExportFormat.PDF.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
            // ClipData carries the grant through the chooser and lets the Sharesheet show a preview.
            clipData = ClipData.newRawUri(subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle)
    }

    /** The Sharesheet for the text summary alone (handy for a quick WhatsApp or SMS message). */
    fun textChooser(subject: String, text: String, chooserTitle: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return Intent.createChooser(send, chooserTitle)
    }

    /**
     * Exports older than a day are removed. Recent ones are kept because some apps (Bluetooth, Quick
     * Share) read the file while the transfer runs, after the Sharesheet has closed.
     */
    internal fun deleteOld(dir: File, now: Long) {
        dir.listFiles()?.filter { now - it.lastModified() > MAX_AGE_MS }?.forEach { it.delete() }
    }

    internal companion object {
        const val TAG = "ReportSharer"
        const val EXPORT_DIR = "exports"
        val MAX_AGE_MS = TimeUnit.DAYS.toMillis(1)
    }
}
