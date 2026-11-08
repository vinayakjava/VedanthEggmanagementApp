package com.eggbusinessmanager.domain.usecase.report

import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.export.ExportFormat
import com.eggbusinessmanager.domain.export.ReportFileName
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.EntryType.BUY
import com.eggbusinessmanager.domain.ledger.EntryType.PAYMENT_MADE
import com.eggbusinessmanager.domain.ledger.EntryType.PAYMENT_RECEIVED
import com.eggbusinessmanager.domain.ledger.EntryType.SELL
import com.eggbusinessmanager.domain.ledger.OpeningDirection
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.payment.PaymentDraft
import com.eggbusinessmanager.domain.report.CustomerStatement
import com.eggbusinessmanager.domain.report.OutstandingReport
import com.eggbusinessmanager.domain.report.PaymentHistoryReport
import com.eggbusinessmanager.domain.report.ReportData
import com.eggbusinessmanager.domain.report.ReportKind
import com.eggbusinessmanager.domain.report.ReportRequest
import com.eggbusinessmanager.domain.report.TradePeriodReport
import com.eggbusinessmanager.domain.report.TradeTotals
import com.eggbusinessmanager.domain.report.TransactionReport
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.trade.TradeDraft
import com.eggbusinessmanager.domain.usecase.customer.CreateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.CustomerChecks
import com.eggbusinessmanager.domain.usecase.entry.EntryWriter
import com.eggbusinessmanager.domain.usecase.payment.PaymentChecks
import com.eggbusinessmanager.domain.usecase.payment.RecordPaymentUseCase
import com.eggbusinessmanager.domain.usecase.trade.DeleteEntryUseCase
import com.eggbusinessmanager.domain.usecase.trade.RecordTradeUseCase
import com.eggbusinessmanager.domain.usecase.trade.TradeChecks
import com.eggbusinessmanager.testing.FakeAuditRepository
import com.eggbusinessmanager.testing.FakeCatalogRepository
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.BROWN
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.TRAY
import com.eggbusinessmanager.testing.FakeCustomerRepository
import com.eggbusinessmanager.testing.FakeLedgerRepository
import com.eggbusinessmanager.testing.FakeReportRepository
import com.eggbusinessmanager.testing.FixedClock
import com.eggbusinessmanager.testing.ImmediateTransactionRunner
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

/** Phase 7 reports: built from entries recorded through the real use cases; no figure is seeded. */
class LoadReportUseCaseTest {

    private val clock = FixedClock()
    private val today = clock.today()
    private val ledger = FakeLedgerRepository()
    private val customers = FakeCustomerRepository(ledger)
    private val audit = FakeAuditRepository()
    private val tx = ImmediateTransactionRunner
    private val writer = EntryWriter(ledger, audit, tx, clock)
    private val recordTrade = RecordTradeUseCase(TradeChecks(customers, FakeCatalogRepository(), clock), writer)
    private val recordPayment = RecordPaymentUseCase(PaymentChecks(customers, clock), writer)
    private val delete = DeleteEntryUseCase(ledger, audit, tx, clock)
    private val createCustomer = CreateCustomerUseCase(CustomerChecks(customers, clock), customers, ledger, audit, tx, clock)
    private val reports = FakeReportRepository(customers, ledger)
    private val load = LoadReportUseCase(reports, ledger, customers, clock)

    private var requests = 0
    private fun rs(rupees: Long) = Money.rupees(rupees)
    private fun day(daysAgo: Long): LocalDate = today.minusDays(daysAgo)

    private suspend fun customer(name: String, opening: String = "", direction: OpeningDirection = OpeningDirection.CUSTOMER_OWES): Long {
        val draft = CustomerDraft(name = name, openingAmount = opening, openingDirection = direction, openingDate = day(40))
        return (createCustomer(draft) as DomainResult.Ok).value
    }

    /** [trays] trays (30 eggs each) for [total] rupees in all. */
    private suspend fun trade(id: Long, type: EntryType, trays: Int, total: Long, date: LocalDate): EntryRef {
        clock.advanceBy(1)
        val price = Money.rupees(total).paise / trays
        val draft = TradeDraft(type, id, date, BROWN, TRAY, trays.toString(), "${price / 100}.${(price % 100).toString().padStart(2, '0')}")
        return (recordTrade(draft, "r${requests++}") as DomainResult.Ok).value
    }

    private suspend fun pay(id: Long, type: EntryType, rupees: Long, date: LocalDate): EntryRef {
        clock.advanceBy(1)
        return (recordPayment(PaymentDraft(type, id, date, rupees.toString()), "r${requests++}") as DomainResult.Ok).value
    }

    private suspend inline fun <reified T : ReportData> report(request: ReportRequest): T = (load(request) as DomainResult.Ok).value as T

    @Test
    fun `statement brings the balance forward, runs it through the range and closes`() = runTest {
        val ram = customer("Ramesh Traders", opening = "1000")
        trade(ram, SELL, 10, 1_800, day(10))
        pay(ram, PAYMENT_RECEIVED, 500, day(5))
        trade(ram, BUY, 2, 300, day(2))
        pay(ram, PAYMENT_MADE, 100, today)
        val gone = pay(ram, PAYMENT_RECEIVED, 999, day(1))
        delete(gone.rootId)

        val s = report<CustomerStatement>(ReportRequest(ReportKind.CUSTOMER_STATEMENT, from = day(7), to = today, customerId = ram))
        assertThat(s.openingBalance).isEqualTo(rs(2_800)) // 1,000 opening + 1,800 sale before the range
        assertThat(s.lines.map { it.entry.reference }).containsExactly("RP-000001", "B-000001", "PC-000001").inOrder()
        assertThat(s.lines.map { it.debit }).containsExactly(Money.ZERO, Money.ZERO, rs(100)).inOrder()
        assertThat(s.lines.map { it.credit }).containsExactly(rs(500), rs(300), Money.ZERO).inOrder()
        assertThat(s.lines.map { it.balance }).containsExactly(rs(2_300), rs(2_000), rs(2_100)).inOrder()
        assertThat(s.closingBalance).isEqualTo(rs(2_100))
        assertThat(s.closingBalance).isEqualTo(s.openingBalance + s.totalDebit - s.totalCredit)
        assertThat(listOf(s.salesTotal, s.purchasesTotal, s.receivedTotal, s.paidTotal))
            .containsExactly(Money.ZERO, rs(300), rs(500), rs(100)).inOrder()
        // The closing balance is the customer's balance today.
        assertThat(s.closingBalance).isEqualTo(customers.observe(ram).first()!!.netBalance)
    }

    @Test
    fun `statement without dates starts at zero and shows the opening balance as its first line`() = runTest {
        val farm = customer("Farm Fresh", opening = "400", direction = OpeningDirection.SHOP_OWES)
        trade(farm, BUY, 4, 600, day(3))
        val s = report<CustomerStatement>(ReportRequest(ReportKind.CUSTOMER_STATEMENT, customerId = farm))
        assertThat(s.openingBalance).isEqualTo(Money.ZERO)
        assertThat(s.lines.first().entry.type).isEqualTo(EntryType.OPENING_BALANCE)
        assertThat(s.lines.first().credit).isEqualTo(rs(400))
        assertThat(s.closingBalance).isEqualTo(-rs(1_000))
    }

    @Test
    fun `statement for a quiet range closes at the opening balance, and a reversed range is accepted`() = runTest {
        val ram = customer("Ramesh Traders", opening = "1000")
        val s = report<CustomerStatement>(ReportRequest(ReportKind.CUSTOMER_STATEMENT, from = day(1), to = day(10), customerId = ram))
        assertThat(s.request.from).isEqualTo(day(10))
        assertThat(s.lines).isEmpty()
        assertThat(s.openingBalance).isEqualTo(rs(1_000))
        assertThat(s.closingBalance).isEqualTo(rs(1_000))
    }

    @Test
    fun `statement needs an existing customer`() = runTest {
        assertThat(load(ReportRequest(ReportKind.CUSTOMER_STATEMENT)))
            .isEqualTo(DomainResult.Err(DomainError.Validation(Field.CUSTOMER, ValidationCode.REQUIRED)))
        assertThat(load(ReportRequest(ReportKind.CUSTOMER_STATEMENT, customerId = 99))).isEqualTo(DomainResult.Err(DomainError.NotFound))
    }

    @Test
    fun `daily sales group by day with every sale, per customer, and ignore purchases and other dates`() = runTest {
        val ram = customer("Ramesh Traders")
        val sita = customer("Sita Stores")
        trade(ram, SELL, 10, 1_800, day(2))
        trade(sita, SELL, 2, 400, day(2))
        trade(ram, SELL, 1, 200, day(1))
        trade(ram, BUY, 5, 700, day(1))
        trade(ram, SELL, 3, 999, day(30))

        val r = report<TradePeriodReport>(ReportRequest(ReportKind.DAILY_SALES, from = day(7), to = today))
        assertThat(r.periods.map { it.start }).containsExactly(day(2), day(1)).inOrder()
        assertThat(r.periods.map { it.totals }).containsExactly(TradeTotals(2, 360, rs(2_200)), TradeTotals(1, 30, rs(200))).inOrder()
        assertThat(r.details.map { it.entry.reference }).containsExactly("S-000001", "S-000002", "S-000003").inOrder()
        assertThat(r.byCustomer.map { it.customerName to it.totals.value })
            .containsExactly("Ramesh Traders" to rs(2_000), "Sita Stores" to rs(400)).inOrder()
        assertThat(r.total).isEqualTo(TradeTotals(3, 390, rs(2_400)))
    }

    @Test
    fun `monthly purchases group by calendar month without entry details`() = runTest {
        val farm = customer("Farm Fresh")
        val thisMonth = today.withDayOfMonth(1)
        trade(farm, BUY, 10, 1_500, thisMonth)
        trade(farm, BUY, 2, 300, today)
        trade(farm, BUY, 4, 600, thisMonth.minusMonths(1).plusDays(14))
        trade(farm, SELL, 1, 100, today)

        val r = report<TradePeriodReport>(ReportRequest(ReportKind.MONTHLY_PURCHASES, from = thisMonth.minusMonths(3)))
        assertThat(r.monthly).isTrue()
        assertThat(r.periods.map { it.start }).containsExactly(thisMonth.minusMonths(1), thisMonth).inOrder()
        assertThat(r.periods.map { it.totals.value }).containsExactly(rs(600), rs(1_800)).inOrder()
        assertThat(r.details).isEmpty()
        assertThat(r.total).isEqualTo(TradeTotals(3, 480, rs(2_400)))
    }

    @Test
    fun `outstanding receivables and payables list every customer with dues, largest first`() = runTest {
        val a = customer("Asha", opening = "300")
        val b = customer("Bharat", opening = "1200")
        val c = customer("Chetan", opening = "500", direction = OpeningDirection.SHOP_OWES)
        val settled = customer("Settled")
        val advance = customer("Advance")
        pay(advance, PAYMENT_RECEIVED, 250, today)
        customers.setActive(b, active = false, at = clock.now()) // deactivated customers still owe money

        val due = report<OutstandingReport>(ReportRequest(ReportKind.RECEIVABLES))
        assertThat(due.rows.map { it.customer.name to it.amount }).containsExactly("Bharat" to rs(1_200), "Asha" to rs(300)).inOrder()
        assertThat(due.total).isEqualTo(rs(1_500))
        assertThat(due.asOf).isEqualTo(today)

        val owed = report<OutstandingReport>(ReportRequest(ReportKind.PAYABLES))
        assertThat(owed.rows.map { it.customer.name to it.amount }).containsExactly("Chetan" to rs(500), "Advance" to rs(250)).inOrder()
        assertThat(owed.total).isEqualTo(rs(750))
        assertThat(listOf(a, c, settled)).hasSize(3)
    }

    @Test
    fun `payment history lists only payments, optionally for one customer, with totals`() = runTest {
        val ram = customer("Ramesh Traders")
        val farm = customer("Farm Fresh")
        trade(ram, SELL, 10, 1_800, day(4))
        pay(ram, PAYMENT_RECEIVED, 500, day(3))
        pay(farm, PAYMENT_MADE, 700, day(2))
        pay(ram, PAYMENT_RECEIVED, 200, day(60))

        val all = report<PaymentHistoryReport>(ReportRequest(ReportKind.PAYMENT_HISTORY, from = day(30)))
        assertThat(all.rows.map { it.entry.reference }).containsExactly("RP-000001", "PC-000001").inOrder()
        assertThat(all.received to all.paid).isEqualTo(rs(500) to rs(700))

        val ramOnly = report<PaymentHistoryReport>(ReportRequest(ReportKind.PAYMENT_HISTORY, customerId = ram))
        assertThat(ramOnly.customer?.name).isEqualTo("Ramesh Traders")
        assertThat(ramOnly.rows.map { it.entry.reference }).containsExactly("RP-000002", "RP-000001").inOrder()
        assertThat(ramOnly.received).isEqualTo(rs(700))
    }

    @Test
    fun `date-range transaction report has every type oldest first with totals`() = runTest {
        val ram = customer("Ramesh Traders", opening = "100")
        trade(ram, SELL, 10, 1_800, day(4))
        trade(ram, BUY, 2, 300, day(3))
        pay(ram, PAYMENT_RECEIVED, 500, day(2))
        pay(ram, PAYMENT_MADE, 50, day(1))
        trade(ram, SELL, 1, 99, day(20))

        val r = report<TransactionReport>(ReportRequest(ReportKind.TRANSACTIONS, from = day(5), to = today))
        assertThat(r.list.rows.map { it.entry.reference }).containsExactly("S-000001", "B-000001", "RP-000001", "PC-000001").inOrder()
        assertThat(r.list.totals.count).isEqualTo(4)
        assertThat(r.list.totals.netEffect).isEqualTo(rs(1_800 - 300 - 500 + 50))
    }

    @Test
    fun `report kinds describe their parameters and file names are safe`() {
        assertThat(ReportKind.entries.filter { it.requiresCustomer }).containsExactly(ReportKind.CUSTOMER_STATEMENT)
        assertThat(ReportKind.entries.filterNot { it.usesDateRange }).containsExactly(ReportKind.RECEIVABLES, ReportKind.PAYABLES)
        assertThat(ReportKind.entries.filter { it.acceptsCustomer })
            .containsExactly(ReportKind.CUSTOMER_STATEMENT, ReportKind.PAYMENT_HISTORY, ReportKind.TRANSACTIONS)
        assertThat(ReportFileName.of(listOf("Statement", "Ramesh / Traders: \"A\"", "2026-11-01"), ExportFormat.PDF))
            .isEqualTo("Statement_Ramesh-Traders-A_2026-11-01.pdf")
        assertThat(ReportFileName.of(listOf("बिक्री", ""), ExportFormat.XLSX)).isEqualTo("बिक्री.xlsx")
        assertThat(ReportFileName.of(listOf("///"), ExportFormat.CSV)).isEqualTo("report.csv")
        assertThat(ReportFileName.of(listOf("x".repeat(200)), ExportFormat.CSV)).hasLength(84)
    }
}
