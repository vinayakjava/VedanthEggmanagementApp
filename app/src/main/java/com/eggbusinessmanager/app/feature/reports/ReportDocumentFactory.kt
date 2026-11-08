// Column weights (relative widths) are layout values, tuned per report; naming each would obscure them.
@file:Suppress("MagicNumber")

package com.eggbusinessmanager.app.feature.reports

import android.content.Context
import androidx.annotation.StringRes
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.payment.modeLabel
import com.eggbusinessmanager.app.feature.trade.entryTypeLabel
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.app.ui.text.ReferenceLabels
import com.eggbusinessmanager.domain.catalog.EggVariety
import com.eggbusinessmanager.domain.catalog.MeasurementUnit
import com.eggbusinessmanager.domain.export.Align
import com.eggbusinessmanager.domain.export.Cell
import com.eggbusinessmanager.domain.export.Column
import com.eggbusinessmanager.domain.export.ExportFormat
import com.eggbusinessmanager.domain.export.ReportDocument
import com.eggbusinessmanager.domain.export.ReportFileName
import com.eggbusinessmanager.domain.export.Table
import com.eggbusinessmanager.domain.ledger.Balance
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.model.account.BusinessProfile
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.money.QuantityParser
import com.eggbusinessmanager.domain.report.CustomerStatement
import com.eggbusinessmanager.domain.report.OutstandingReport
import com.eggbusinessmanager.domain.report.PaymentHistoryReport
import com.eggbusinessmanager.domain.report.ReportData
import com.eggbusinessmanager.domain.report.ReportKind
import com.eggbusinessmanager.domain.report.ReportRequest
import com.eggbusinessmanager.domain.report.TradePeriodReport
import com.eggbusinessmanager.domain.report.TradeTotals
import com.eggbusinessmanager.domain.report.TransactionReport
import com.eggbusinessmanager.domain.report.TransactionRow
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Names of units and varieties, for trade details. */
data class Catalog(val units: Map<Long, MeasurementUnit> = emptyMap(), val varieties: Map<Long, EggVariety> = emptyMap())

/** Report title for a kind. */
@StringRes
fun reportTitle(kind: ReportKind): Int = when (kind) {
    ReportKind.CUSTOMER_STATEMENT -> R.string.rk_statement
    ReportKind.DAILY_SALES -> R.string.rk_daily_sales
    ReportKind.DAILY_PURCHASES -> R.string.rk_daily_purchases
    ReportKind.MONTHLY_SALES -> R.string.rk_monthly_sales
    ReportKind.MONTHLY_PURCHASES -> R.string.rk_monthly_purchases
    ReportKind.RECEIVABLES -> R.string.rk_receivables
    ReportKind.PAYABLES -> R.string.rk_payables
    ReportKind.PAYMENT_HISTORY -> R.string.rk_payments
    ReportKind.TRANSACTIONS -> R.string.rk_transactions
}

/**
 * Turns built report data into a [ReportDocument] in the app's language: Indian number and date
 * formatting for display, typed values kept for spreadsheets.
 */
class ReportDocumentFactory @Inject constructor(@ApplicationContext private val context: Context) {

    private val locale get() = context.resources.configuration.locales[0]
    private fun s(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    fun build(data: ReportData, profile: BusinessProfile?, catalog: Catalog, now: LocalDateTime): ReportDocument {
        val (summary, tables, footnote) = when (data) {
            is CustomerStatement -> statement(data, catalog)
            is TradePeriodReport -> trades(data, catalog)
            is OutstandingReport -> outstanding(data)
            is PaymentHistoryReport -> payments(data)
            is TransactionReport -> transactions(data, catalog)
        }
        return ReportDocument(
            title = s(reportTitle(data.request.kind)),
            business = business(profile),
            details = details(data),
            summary = summary,
            tables = tables,
            generatedAt = s(R.string.doc_generated, DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).format(now)),
            footnote = footnote,
        )
    }

    /** E.g. "Customer-statement_Ramesh-Traders_2026-11-01_2026-11-30.pdf". */
    fun fileName(data: ReportData, format: ExportFormat): String {
        val r = data.request
        val who = when (data) {
            is CustomerStatement -> data.customer.name
            is PaymentHistoryReport -> data.customer?.name.orEmpty()
            is TransactionReport -> data.customer?.name.orEmpty()
            else -> ""
        }
        val dates = if (r.kind.usesDateRange) listOfNotNull(r.from?.toString(), r.to?.toString()) else listOf(r.to?.toString() ?: "")
        return ReportFileName.of(listOf(s(reportTitle(r.kind))) + who + dates, format)
    }

    private data class Parts(val summary: List<Pair<String, Cell>>, val tables: List<Table>, val footnote: String? = null)

    // ---- Header ----

    private fun business(profile: BusinessProfile?): List<String> {
        if (profile == null) return emptyList()
        val place = listOfNotNull(
            profile.address?.takeIf { it.isNotBlank() },
            profile.city,
            "${s(ReferenceLabels.state(profile.state))} ${profile.pinCode}",
        ).joinToString(", ")
        return listOf(profile.shopName, place)
    }

    private fun details(data: ReportData): List<String> {
        val r = data.request
        val period = if (r.kind.usesDateRange) period(r) else s(R.string.doc_as_of, date((data as OutstandingReport).asOf))
        val customer = when (data) {
            is CustomerStatement -> data.customer
            is PaymentHistoryReport -> data.customer
            is TransactionReport -> data.customer
            else -> null
        }
        val mobile = customer?.mobile?.takeIf { it.isNotBlank() }
        val who = when {
            customer != null && mobile != null -> s(R.string.doc_customer_mobile, customer.name, mobile)
            customer != null -> s(R.string.doc_customer, customer.name)
            r.kind.acceptsCustomer -> s(R.string.doc_customer, s(R.string.doc_all_customers))
            else -> null
        }
        return listOfNotNull(who, period)
    }

    private fun period(r: ReportRequest): String {
        val from = r.from
        val to = r.to
        return when {
            from != null && to != null -> s(R.string.doc_period, date(from), date(to))
            from != null -> s(R.string.doc_period_from, date(from))
            to != null -> s(R.string.doc_period_to, date(to))
            else -> s(R.string.doc_period_all)
        }
    }

    // ---- Customer statement ----

    private fun statement(d: CustomerStatement, catalog: Catalog): Parts {
        val columns = listOf(
            Column(s(R.string.doc_col_date), 1.15f),
            Column(s(R.string.doc_col_ref), 1.05f),
            Column(s(R.string.doc_col_particulars), 3f),
            Column(s(R.string.doc_col_debit), 1.25f, Align.END),
            Column(s(R.string.doc_col_credit), 1.25f, Align.END),
            Column(s(R.string.doc_col_balance), 1.5f, Align.END),
        )
        val rows = buildList {
            d.request.from?.let { start ->
                val broughtForward = Cell.Text(s(R.string.doc_brought_forward))
                add(listOf(day(start), Cell.EMPTY, broughtForward, Cell.EMPTY, Cell.EMPTY, drCr(d.openingBalance)))
            }
            d.lines.forEach { line ->
                add(
                    listOf(
                        day(line.entry.date),
                        Cell.Text(line.entry.reference),
                        Cell.Text(particulars(line.entry, catalog)),
                        if (line.debit.isZero) Cell.EMPTY else amount(line.debit),
                        if (line.credit.isZero) Cell.EMPTY else amount(line.credit),
                        drCr(line.balance),
                    ),
                )
            }
        }
        val table = Table(
            heading = null,
            columns = columns,
            rows = rows,
            footer = listOf(total(), Cell.EMPTY, Cell.EMPTY, amount(d.totalDebit), amount(d.totalCredit), drCr(d.closingBalance)),
            emptyText = s(R.string.doc_none),
        )
        val summary = listOf(
            s(R.string.doc_opening) to balance(d.openingBalance),
            s(R.string.doc_closing) to balance(d.closingBalance),
            s(R.string.ledger_sales) to amount(d.salesTotal),
            s(R.string.ledger_purchases) to amount(d.purchasesTotal),
            s(R.string.ledger_received) to amount(d.receivedTotal),
            s(R.string.ledger_paid) to amount(d.paidTotal),
        )
        return Parts(summary, listOf(table), s(R.string.doc_statement_note))
    }

    /** "Sale · Brown · 10 Tray (300 eggs) @ ₹180.00", "Payment received · UPI · Ref. 123", remarks appended. */
    private fun particulars(e: LedgerEntry, catalog: Catalog): String {
        val parts = mutableListOf(s(entryTypeLabel(e.type)))
        e.trade?.let { t ->
            val unit = catalog.units[t.unitId]?.name.orEmpty()
            catalog.varieties[t.varietyId]?.name?.let(parts::add)
            parts += s(R.string.doc_quantity, QuantityParser.toInput(t.quantity), unit, IndianFormatters.group(t.pieces)) +
                " @ " + IndianFormatters.money(t.pricePerUnit)
        }
        e.payment?.let { p ->
            parts += s(modeLabel(p.mode))
            p.reference?.let { parts += s(R.string.doc_col_ref) + " " + it }
        }
        e.remarks?.takeIf { it.isNotBlank() }?.let(parts::add)
        return parts.joinToString(" · ")
    }

    // ---- Daily / monthly sales and purchases ----

    private fun trades(d: TradePeriodReport, catalog: Catalog): Parts {
        val countColumns = listOf(
            Column(s(R.string.doc_entries), 1f, Align.END),
            Column(s(R.string.doc_eggs), 1.2f, Align.END),
            Column(s(R.string.doc_col_amount), 1.6f, Align.END),
        )
        fun totalsCells(t: TradeTotals) = listOf(count(t.count.toLong()), count(t.eggs), amount(t.value))
        val monthFormat = DateTimeFormatter.ofPattern("MMM yyyy", locale)
        val byPeriod = Table(
            heading = s(if (d.monthly) R.string.doc_by_month else R.string.doc_by_day),
            columns = listOf(Column(s(if (d.monthly) R.string.doc_col_month else R.string.doc_col_date), 2f)) + countColumns,
            rows = d.periods.map { p ->
                listOf(if (d.monthly) Cell.Text(monthFormat.format(p.start)) else day(p.start)) + totalsCells(p.totals)
            },
            footer = listOf(Cell.Text(s(R.string.doc_total))) + totalsCells(d.total),
            emptyText = s(R.string.doc_none),
        )
        val byCustomer = Table(
            heading = s(R.string.doc_by_customer),
            columns = listOf(Column(s(R.string.doc_col_customer), 2f)) + countColumns,
            rows = d.byCustomer.map { listOf(Cell.Text(it.customerName)) + totalsCells(it.totals) },
            footer = listOf(Cell.Text(s(R.string.doc_total))) + totalsCells(d.total),
            emptyText = s(R.string.doc_none),
        )
        val tables = mutableListOf(byPeriod, byCustomer)
        if (!d.monthly) {
            tables += Table(
                heading = s(R.string.doc_all_entries),
                columns = listOf(
                    Column(s(R.string.doc_col_date), 1.1f),
                    Column(s(R.string.doc_col_ref), 1f),
                    Column(s(R.string.doc_col_customer), 2f),
                    Column(s(R.string.doc_col_variety), 1.1f),
                    Column(s(R.string.doc_col_quantity), 1.9f),
                    Column(s(R.string.doc_col_rate), 1.1f, Align.END),
                    Column(s(R.string.doc_col_amount), 1.3f, Align.END),
                ),
                rows = d.details.map { row -> tradeRow(row, catalog) },
                footer = listOf(total()) + List(5) { Cell.EMPTY } + amount(d.total.value),
                emptyText = s(R.string.doc_none),
            )
        }
        val summary = listOf(
            s(R.string.doc_total_value) to amount(d.total.value),
            s(R.string.doc_entries) to count(d.total.count.toLong()),
            s(R.string.doc_eggs) to count(d.total.eggs),
        )
        return Parts(summary, tables)
    }

    private fun tradeRow(row: TransactionRow, catalog: Catalog): List<Cell> {
        val e = row.entry
        val t = checkNotNull(e.trade)
        val unit = catalog.units[t.unitId]?.name.orEmpty()
        return listOf(
            day(e.date),
            Cell.Text(e.reference),
            Cell.Text(row.customerName),
            Cell.Text(catalog.varieties[t.varietyId]?.name.orEmpty()),
            Cell.Text(s(R.string.doc_quantity, QuantityParser.toInput(t.quantity), unit, IndianFormatters.group(t.pieces))),
            amount(t.pricePerUnit),
            amount(e.amount),
        )
    }

    // ---- Outstanding ----

    private fun outstanding(d: OutstandingReport): Parts {
        val table = Table(
            heading = null,
            columns = listOf(
                Column(s(R.string.doc_col_customer), 2.6f),
                Column(s(R.string.doc_col_mobile), 1.5f),
                Column(s(R.string.doc_col_last_activity), 1.4f),
                Column(s(R.string.doc_col_amount), 1.6f, Align.END),
            ),
            rows = d.rows.map { r ->
                val last = r.lastActivity?.let(::day) ?: Cell.EMPTY
                listOf(Cell.Text(r.customer.name), Cell.Text(r.customer.mobile.orEmpty()), last, amount(r.amount))
            },
            footer = listOf(Cell.Text(s(R.string.doc_total)), Cell.EMPTY, Cell.EMPTY, amount(d.total)),
            emptyText = s(R.string.doc_none_due),
        )
        val summary = listOf(
            s(R.string.doc_total) to amount(d.total),
            s(R.string.doc_customers) to count(d.rows.size.toLong()),
        )
        return Parts(summary, listOf(table))
    }

    // ---- Payment history ----

    private fun payments(d: PaymentHistoryReport): Parts {
        val table = Table(
            heading = null,
            columns = listOf(
                Column(s(R.string.doc_col_date), 1.1f),
                Column(s(R.string.doc_col_ref), 1.05f),
                Column(s(R.string.doc_col_customer), 2f),
                Column(s(R.string.doc_col_mode), 1.1f),
                Column(s(R.string.doc_col_payment_ref), 1.4f),
                Column(s(R.string.doc_col_received), 1.3f, Align.END),
                Column(s(R.string.doc_col_paid), 1.3f, Align.END),
            ),
            rows = d.rows.map { row ->
                val e = row.entry
                val p = checkNotNull(e.payment)
                val received = e.type == EntryType.PAYMENT_RECEIVED
                listOf(
                    day(e.date),
                    Cell.Text(e.reference),
                    Cell.Text(row.customerName),
                    Cell.Text(s(modeLabel(p.mode))),
                    Cell.Text(p.reference.orEmpty()),
                    if (received) amount(e.amount) else Cell.EMPTY,
                    if (received) Cell.EMPTY else amount(e.amount),
                )
            },
            footer = listOf(total()) + List(4) { Cell.EMPTY } + listOf(amount(d.received), amount(d.paid)),
            emptyText = s(R.string.doc_none),
        )
        val summary = listOf(
            s(R.string.ledger_received) to amount(d.received),
            s(R.string.ledger_paid) to amount(d.paid),
            s(R.string.doc_net) to amount(d.received - d.paid),
            s(R.string.doc_entries) to count(d.rows.size.toLong()),
        )
        return Parts(summary, listOf(table))
    }

    // ---- Date-range transactions ----

    private fun transactions(d: TransactionReport, catalog: Catalog): Parts {
        val table = Table(
            heading = null,
            columns = listOf(
                Column(s(R.string.doc_col_date), 1.1f),
                Column(s(R.string.doc_col_ref), 1.05f),
                Column(s(R.string.doc_col_customer), 1.9f),
                Column(s(R.string.doc_col_type), 1.4f),
                Column(s(R.string.doc_col_details), 2.4f),
                Column(s(R.string.doc_col_amount), 1.4f, Align.END),
            ),
            rows = d.list.rows.map { row ->
                val e = row.entry
                val detail = e.trade?.let { t ->
                    val unit = catalog.units[t.unitId]?.name.orEmpty()
                    s(R.string.doc_quantity, QuantityParser.toInput(t.quantity), unit, IndianFormatters.group(t.pieces))
                } ?: e.payment?.let { p -> listOfNotNull(s(modeLabel(p.mode)), p.reference).joinToString(" · ") }
                listOf(
                    day(e.date),
                    Cell.Text(e.reference),
                    Cell.Text(row.customerName),
                    Cell.Text(s(entryTypeLabel(e.type))),
                    Cell.Text(detail.orEmpty()),
                    amount(e.amount),
                )
            },
            emptyText = s(R.string.doc_none),
        )
        val t = d.list.totals
        val summary = listOf(
            s(R.string.ledger_sales) to amount(t.sales),
            s(R.string.ledger_purchases) to amount(t.purchases),
            s(R.string.ledger_received) to amount(t.received),
            s(R.string.ledger_paid) to amount(t.paid),
            s(R.string.doc_entries) to count(t.count.toLong()),
        )
        return Parts(summary, listOf(table))
    }

    // ---- Cells ----

    private fun total() = Cell.Text(s(R.string.doc_total))
    private fun date(d: LocalDate) = IndianFormatters.date(d, locale)
    private fun day(d: LocalDate) = Cell.Day(d, date(d))
    private fun amount(m: Money) = Cell.Amount(m, IndianFormatters.money(m))
    private fun count(n: Long) = Cell.Count(n, IndianFormatters.group(n))

    /** A balance as "₹1,200.00 Dr" (customer owes) or "₹300.00 Cr" (shop owes); the raw value keeps its sign. */
    private fun drCr(m: Money): Cell = Cell.Amount(
        m,
        when {
            m.isPositive -> s(R.string.doc_dr, IndianFormatters.money(m))
            m.isNegative -> s(R.string.doc_cr, IndianFormatters.money(m.abs()))
            else -> IndianFormatters.money(m)
        },
    )

    /** Headline balance in words: "To receive ₹…", "To pay ₹…" or "Settled". */
    private fun balance(m: Money): Cell = Cell.Amount(
        m,
        when (val b = Balance.of(m)) {
            is Balance.ToReceive -> s(R.string.balance_to_receive, IndianFormatters.money(b.amount))
            is Balance.ToPay -> s(R.string.balance_to_pay, IndianFormatters.money(b.amount))
            Balance.Settled -> s(R.string.balance_settled)
        },
    )
}
