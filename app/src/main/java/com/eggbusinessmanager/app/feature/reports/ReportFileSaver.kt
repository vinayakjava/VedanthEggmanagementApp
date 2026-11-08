package com.eggbusinessmanager.app.feature.reports

import android.content.Context
import android.net.Uri
import com.eggbusinessmanager.domain.export.ExportFormat
import com.eggbusinessmanager.domain.export.ReportDocument
import com.eggbusinessmanager.domain.export.ReportExporter
import com.eggbusinessmanager.domain.logging.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/**
 * Writes a report to a document the owner picked with the Storage Access Framework (any folder on the
 * device, SD card or a cloud drive that offers documents). The app needs no storage permission: the
 * picker grants access to just that one file.
 */
class ReportFileSaver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exporter: ReportExporter,
    private val logger: AppLogger,
) {
    /** True when the file was written completely. Truncates the target first ("wt"). */
    suspend fun save(uri: Uri, document: ReportDocument, format: ExportFormat): Boolean = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return@withContext false
            stream.use { exporter.export(document, format, it) }
            true
        } catch (e: IOException) {
            logger.warn(TAG, e) { "Report export failed" }
            false
        } catch (e: SecurityException) {
            logger.warn(TAG, e) { "Report export not permitted" }
            false
        }
    }

    private companion object {
        const val TAG = "ReportFileSaver"
    }
}
