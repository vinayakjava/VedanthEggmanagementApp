package com.eggbusinessmanager.data.export

import com.eggbusinessmanager.domain.export.ExportFormat
import com.eggbusinessmanager.domain.export.ReportDocument
import com.eggbusinessmanager.domain.export.ReportExporter
import java.io.OutputStream
import javax.inject.Inject

/** Picks the writer for the format. Callers run it off the main thread. */
class FileReportExporter @Inject constructor() : ReportExporter {
    override fun export(document: ReportDocument, format: ExportFormat, out: OutputStream) = when (format) {
        ExportFormat.PDF -> PdfReportWriter.write(document, out)
        ExportFormat.CSV -> CsvReportWriter.write(document, out)
        ExportFormat.XLSX -> XlsxReportWriter.write(document, out)
    }
}
