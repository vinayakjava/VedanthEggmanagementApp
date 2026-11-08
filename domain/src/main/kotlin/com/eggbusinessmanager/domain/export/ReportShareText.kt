package com.eggbusinessmanager.domain.export

/**
 * The readable message that travels with a shared report (Phase 8): shop, report, customer and period,
 * then the headline figures — enough to understand the report in a chat without opening the PDF.
 * Plain text only, so it reads the same in WhatsApp, e-mail and SMS.
 */
object ReportShareText {

    /** E.g. "Customer statement – Ramesh Traders · 1 Nov 2026 – 30 Nov 2026 – Patil Egg Centre". */
    fun subject(document: ReportDocument): String =
        (listOf(document.title) + document.details.take(1).map(::withoutLabel) + document.business.take(1))
            .filter { it.isNotBlank() }
            .joinToString(" – ")

    /** [closing] is appended after a blank line, e.g. "PDF attached." or the generated-at line. */
    fun body(document: ReportDocument, closing: String?): String = buildString {
        document.business.firstOrNull()?.let { appendLine(it) }
        appendLine(document.title)
        document.details.forEach { appendLine(it) }
        if (document.summary.isNotEmpty()) {
            appendLine()
            document.summary.forEach { (label, value) -> appendLine("$label: ${value.display}") }
        }
        if (!closing.isNullOrBlank()) {
            appendLine()
            append(closing)
        }
    }.trimEnd()

    /** "Customer: Ramesh Traders · 98765…" → "Ramesh Traders · 98765…" (labels are noise in a subject line). */
    private fun withoutLabel(line: String): String = line.substringAfter(": ", line)
}
