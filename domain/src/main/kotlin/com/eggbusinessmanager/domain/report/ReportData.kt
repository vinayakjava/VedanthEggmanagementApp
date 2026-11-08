package com.eggbusinessmanager.domain.report

import com.eggbusinessmanager.domain.customer.Customer
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerCalculator
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.validation.SearchKey
import java.time.LocalDate
import java.time.YearMonth

/** The reports the owner can view and export (Phase 7). */
enum class ReportKind {
    CUSTOMER_STATEMENT,
    DAILY_SALES,
    DAILY_PURCHASES,
    MONTHLY_SALES,
    MONTHLY_PURCHASES,
    RECEIVABLES,
    PAYABLES,
    PAYMENT_HISTORY,
    TRANSACTIONS,
    ;

    /** A customer statement is always for one customer. */
    val requiresCustomer: Boolean get() = this == CUSTOMER_STATEMENT

    /** Reports that can optionally be narrowed to one customer. */
    val acceptsCustomer: Boolean get() = this in setOf(CUSTOMER_STATEMENT, PAYMENT_HISTORY, TRANSACTIONS)

    /** Outstanding balances are "as of today"; every other report covers a date range. */
    val usesDateRange: Boolean get() = this != RECEIVABLES && this != PAYABLES
}

/** What to build. Empty dates mean "from the beginning" / "up to today". */
data class ReportRequest(
    val kind: ReportKind,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val customerId: Long? = null,
) {
    fun normalized(): ReportRequest = if (from != null && to != null && from > to) copy(from = to, to = from) else this
}

/** Built report contents: business figures only, no formatting or language. */
sealed interface ReportData {
    val request: ReportRequest
}

/** One statement line: debit raises what the customer owes (sales, payments made), credit lowers it. */
data class StatementLine(val entry: LedgerEntry, val debit: Money, val credit: Money, val balance: Money)

/**
 * A customer statement: balance brought forward at the start of the range, every active entry in the
 * range with the running balance after it, and the closing balance. Opening balances carried over from
 * the paper book appear as their own line when dated inside the range, and in the brought-forward
 * figure when dated before it. Closing = opening + Σ debits − Σ credits.
 */
data class CustomerStatement(
    override val request: ReportRequest,
    val customer: Customer,
    val openingBalance: Money,
    val lines: List<StatementLine>,
    val closingBalance: Money,
    val totalDebit: Money,
    val totalCredit: Money,
    val salesTotal: Money,
    val purchasesTotal: Money,
    val receivedTotal: Money,
    val paidTotal: Money,
) : ReportData

/** Sales or purchases in one day or month. */
data class PeriodRow(val start: LocalDate, val totals: TradeTotals)

/** Sales or purchases per customer within the range. */
data class CustomerTradeRow(val customerName: String, val totals: TradeTotals)

/** Daily or monthly sales or purchases: one row per period with activity, per customer, and every entry for daily reports. */
data class TradePeriodReport(
    override val request: ReportRequest,
    val type: EntryType,
    val monthly: Boolean,
    val periods: List<PeriodRow>,
    val byCustomer: List<CustomerTradeRow>,
    val details: List<TransactionRow>,
    val total: TradeTotals,
) : ReportData

data class OutstandingRow(val customer: Customer, val amount: Money, val lastActivity: LocalDate?)

/** Customers who owe the shop ([receivable]) or whom the shop owes, largest first, with the total. */
data class OutstandingReport(
    override val request: ReportRequest,
    val receivable: Boolean,
    val asOf: LocalDate,
    val rows: List<OutstandingRow>,
    val total: Money,
) : ReportData

data class PaymentHistoryReport(
    override val request: ReportRequest,
    val customer: Customer?,
    val rows: List<TransactionRow>,
    val received: Money,
    val paid: Money,
) : ReportData

data class TransactionReport(
    override val request: ReportRequest,
    val customer: Customer?,
    val list: TransactionList,
) : ReportData

/** Pure report builders; loading is done by the use case. */
object ReportBuilder {

    fun statement(request: ReportRequest, customer: Customer, entries: List<LedgerEntry>): CustomerStatement {
        val all = LedgerCalculator.runningLines(entries)
        val before = all.filter { request.from != null && it.entry.date < request.from }
        val inRange = all.filter {
            (request.from == null || it.entry.date >= request.from) && (request.to == null || it.entry.date <= request.to)
        }
        val opening = before.lastOrNull()?.runningBalance ?: Money.ZERO
        val lines = inRange.map { line ->
            val effect = line.entry.balanceEffect
            StatementLine(
                entry = line.entry,
                debit = if (effect.isPositive) effect else Money.ZERO,
                credit = if (effect.isNegative) -effect else Money.ZERO,
                balance = line.runningBalance,
            )
        }
        fun sum(type: EntryType) = lines.filter { it.entry.type == type }.fold(Money.ZERO) { s, l -> s + l.entry.amount }
        return CustomerStatement(
            request = request,
            customer = customer,
            openingBalance = opening,
            lines = lines,
            closingBalance = lines.lastOrNull()?.balance ?: opening,
            totalDebit = lines.fold(Money.ZERO) { s, l -> s + l.debit },
            totalCredit = lines.fold(Money.ZERO) { s, l -> s + l.credit },
            salesTotal = sum(EntryType.SELL),
            purchasesTotal = sum(EntryType.BUY),
            receivedTotal = sum(EntryType.PAYMENT_RECEIVED),
            paidTotal = sum(EntryType.PAYMENT_MADE),
        )
    }

    /** [rows] are the active trades of [type] in the range. */
    fun tradePeriods(request: ReportRequest, type: EntryType, monthly: Boolean, rows: List<TransactionRow>): TradePeriodReport {
        val trades = rows.filter { it.entry.type == type }
        val oldestFirst = trades.sortedWith(TransactionSort.OLDEST.comparator())
        val periods = oldestFirst.groupBy { if (monthly) YearMonth.from(it.entry.date).atDay(1) else it.entry.date }
            .map { (start, list) -> PeriodRow(start, totals(list)) }
        val byCustomer = trades.groupBy { it.entry.customerId }.values
            .map { list -> CustomerTradeRow(list.first().customerName, totals(list)) }
            .sortedWith(compareByDescending<CustomerTradeRow> { it.totals.value }.thenBy { SearchKey.of(it.customerName) })
        return TradePeriodReport(
            request = request,
            type = type,
            monthly = monthly,
            periods = periods,
            byCustomer = byCustomer,
            details = if (monthly) emptyList() else oldestFirst,
            total = totals(trades),
        )
    }

    fun outstanding(request: ReportRequest, summaries: List<CustomerSummary>, receivable: Boolean, asOf: LocalDate): OutstandingReport {
        val rows = summaries
            .filter { if (receivable) it.netBalance.isPositive else it.netBalance.isNegative }
            .map { OutstandingRow(it.customer, it.netBalance.abs(), it.lastActivity) }
            .sortedWith(compareByDescending<OutstandingRow> { it.amount }.thenBy { SearchKey.of(it.customer.name) })
        return OutstandingReport(request, receivable, asOf, rows, rows.fold(Money.ZERO) { s, r -> s + r.amount })
    }

    fun paymentHistory(request: ReportRequest, customer: Customer?, rows: List<TransactionRow>): PaymentHistoryReport {
        val payments = rows.filter { it.entry.payment != null }.sortedWith(TransactionSort.OLDEST.comparator())
        val totals = TransactionTotals.of(payments)
        return PaymentHistoryReport(request, customer, payments, totals.received, totals.paid)
    }

    fun transactions(request: ReportRequest, customer: Customer?, rows: List<TransactionRow>): TransactionReport {
        val sorted = rows.sortedWith(TransactionSort.OLDEST.comparator())
        return TransactionReport(request, customer, TransactionList(sorted, TransactionTotals.of(sorted)))
    }

    private fun totals(rows: List<TransactionRow>) = TradeTotals(
        count = rows.size,
        eggs = rows.sumOf { it.entry.trade?.pieces ?: 0 },
        value = rows.fold(Money.ZERO) { s, r -> s + r.entry.amount },
    )
}
