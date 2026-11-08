package com.eggbusinessmanager.domain.report

import com.eggbusinessmanager.domain.customer.Customer
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.validation.SearchKey
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** The entry types the Transactions screen lists: trades and payments (opening balances are not transactions). */
val TRANSACTION_TYPES: Set<EntryType> = setOf(EntryType.SELL, EntryType.BUY, EntryType.PAYMENT_RECEIVED, EntryType.PAYMENT_MADE)

/** One active transaction with its customer, for lists. */
data class TransactionRow(val entry: LedgerEntry, val customerName: String, val customerMobile: String?)

enum class TransactionSort { NEWEST, OLDEST, AMOUNT_HIGH, AMOUNT_LOW, CUSTOMER }

/**
 * What the Transactions screen shows. Every condition narrows the list; empty values mean "any".
 * [search] matches the customer's name (case-insensitive, anywhere) or, for digits, the mobile number.
 * The repository applies the same rules in SQL; [matches] is the reference definition.
 */
data class TransactionFilter(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val customerId: Long? = null,
    val types: Set<EntryType> = TRANSACTION_TYPES,
    val search: String = "",
    val sort: TransactionSort = TransactionSort.NEWEST,
) {
    init {
        require(types.all { it in TRANSACTION_TYPES }) { "Only trades and payments are transactions" }
    }

    /** A start after the end is read as the same range the other way round. */
    fun normalized(): TransactionFilter = if (from != null && to != null && from > to) copy(from = to, to = from) else this

    val searchText: String get() = SearchKey.of(search)
    val searchDigits: String get() = SearchKey.digits(search)

    fun matches(row: TransactionRow): Boolean {
        val e = row.entry
        return e.status == EntryStatus.ACTIVE && e.type in types &&
            (from == null || !e.date.isBefore(from)) && (to == null || !e.date.isAfter(to)) &&
            (customerId == null || e.customerId == customerId) && matchesSearch(row)
    }

    private fun matchesSearch(row: TransactionRow): Boolean {
        val text = searchText
        val digits = searchDigits
        return text.isEmpty() || SearchKey.of(row.customerName).contains(text) ||
            (digits.isNotEmpty() && row.customerMobile?.contains(digits) == true)
    }
}

/** Sorting is defined here once; ties fall back to newest first so the order is always stable. */
fun TransactionSort.comparator(): Comparator<TransactionRow> {
    val newest = compareByDescending<TransactionRow> { it.entry.date }
        .thenByDescending { it.entry.createdAt }
        .thenByDescending { it.entry.id }
    return when (this) {
        TransactionSort.NEWEST -> newest
        TransactionSort.OLDEST -> newest.reversed()
        TransactionSort.AMOUNT_HIGH -> compareByDescending<TransactionRow> { it.entry.amount.paise }.then(newest)
        TransactionSort.AMOUNT_LOW -> compareBy<TransactionRow> { it.entry.amount.paise }.then(newest)
        TransactionSort.CUSTOMER -> compareBy<TransactionRow> { SearchKey.of(it.customerName) }.then(newest)
    }
}

/** Totals of exactly the listed (filtered) transactions. Eggs are whole pieces. */
data class TransactionTotals(
    val count: Int = 0,
    val sales: Money = Money.ZERO,
    val purchases: Money = Money.ZERO,
    val received: Money = Money.ZERO,
    val paid: Money = Money.ZERO,
    val eggsSold: Long = 0,
    val eggsBought: Long = 0,
) {
    /** How the listed transactions move balances in total (positive = customers owe the shop more). */
    val netEffect: Money get() = sales - purchases - received + paid

    companion object {
        fun of(rows: List<TransactionRow>): TransactionTotals = rows.fold(TransactionTotals()) { t, row ->
            val e = row.entry
            val pieces = e.trade?.pieces ?: 0
            when (e.type) {
                EntryType.SELL -> t.copy(sales = t.sales + e.amount, eggsSold = t.eggsSold + pieces)
                EntryType.BUY -> t.copy(purchases = t.purchases + e.amount, eggsBought = t.eggsBought + pieces)
                EntryType.PAYMENT_RECEIVED -> t.copy(received = t.received + e.amount)
                EntryType.PAYMENT_MADE -> t.copy(paid = t.paid + e.amount)
                EntryType.OPENING_BALANCE -> t
            }.copy(count = t.count + 1)
        }
    }
}

data class TransactionList(val rows: List<TransactionRow>, val totals: TransactionTotals)

/** Count, eggs and value of sales or of purchases. */
data class TradeTotals(val count: Int = 0, val eggs: Long = 0, val value: Money = Money.ZERO)

/**
 * The dashboard (FR-040): everything is derived from customers and active ledger entries; nothing is
 * stored. Receivables and payables are the sums of positive and negative customer balances, so a
 * customer's advance shows under payables, never netted against other customers' dues.
 */
data class DashboardSummary(
    val totalCustomers: Int = 0,
    val activeCustomers: Int = 0,
    val sales: TradeTotals = TradeTotals(),
    val purchases: TradeTotals = TradeTotals(),
    /** Sales, purchases and payments in the selected [DashboardPeriod] (US6-1). */
    val periodSales: TradeTotals = TradeTotals(),
    val periodPurchases: TradeTotals = TradeTotals(),
    val periodReceived: Money = Money.ZERO,
    val periodPaid: Money = Money.ZERO,
    val receivables: Money = Money.ZERO,
    val payables: Money = Money.ZERO,
    val receivableCustomers: Int = 0,
    val payableCustomers: Int = 0,
    val recent: List<TransactionRow> = emptyList(),
    /** The customers who owe the most, largest first (US6-2). */
    val topReceivables: List<CustomerSummary> = emptyList(),
) {
    /** Net cash movement in the period: received − paid (US6-1). */
    val netCash: Money get() = periodReceived - periodPaid
}

/** The dashboard's period (US6-1). Today is the default; a week starts on Monday; all end today. */
enum class DashboardPeriod {
    TODAY,
    WEEK,
    MONTH,
    ;

    fun from(today: LocalDate): LocalDate = when (this) {
        TODAY -> today
        WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        MONTH -> today.withDayOfMonth(1)
    }
}

/** The reference calculation of [DashboardSummary]; the SQL in the data layer must agree with it. */
object DashboardCalculator {
    const val RECENT_LIMIT = 10
    const val TOP_LIMIT = 5

    /** The customers with the largest positive balances, largest first. */
    fun topReceivables(balances: List<CustomerSummary>): List<CustomerSummary> =
        balances.filter { it.netBalance.isPositive }.sortedByDescending { it.netBalance }.take(TOP_LIMIT)

    fun of(
        customers: List<Customer>,
        entries: List<LedgerEntry>,
        from: LocalDate,
        to: LocalDate,
        recentLimit: Int = RECENT_LIMIT,
    ): DashboardSummary {
        val active = entries.filter { it.status == EntryStatus.ACTIVE }
        val balances = active.groupBy { it.customerId }.values.map { list -> list.fold(Money.ZERO) { sum, e -> sum + e.balanceEffect } }
        val byId = customers.associateBy { it.id }
        val recent = active.filter { it.type in TRANSACTION_TYPES }
            .sortedWith(compareByDescending<LedgerEntry> { it.createdAt }.thenByDescending { it.id })
            .take(recentLimit)
            .map { e -> TransactionRow(e, byId[e.customerId]?.name.orEmpty(), byId[e.customerId]?.mobile) }
        val period = active.filter { it.date in from..to }
        return DashboardSummary(
            totalCustomers = customers.size,
            activeCustomers = customers.count { it.isActive },
            sales = trades(active, EntryType.SELL),
            purchases = trades(active, EntryType.BUY),
            periodSales = trades(period, EntryType.SELL),
            periodPurchases = trades(period, EntryType.BUY),
            periodReceived = trades(period, EntryType.PAYMENT_RECEIVED).value,
            periodPaid = trades(period, EntryType.PAYMENT_MADE).value,
            receivables = balances.filter { it.isPositive }.fold(Money.ZERO, Money::plus),
            payables = balances.filter { it.isNegative }.fold(Money.ZERO) { sum, b -> sum - b },
            receivableCustomers = balances.count { it.isPositive },
            payableCustomers = balances.count { it.isNegative },
            recent = recent,
        )
    }

    private fun trades(entries: List<LedgerEntry>, type: EntryType): TradeTotals {
        val list = entries.filter { it.type == type }
        return TradeTotals(list.size, list.sumOf { it.trade?.pieces ?: 0 }, list.fold(Money.ZERO) { sum, e -> sum + e.amount })
    }
}
