package com.eggbusinessmanager.domain.usecase.report

import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.EntryType.BUY
import com.eggbusinessmanager.domain.ledger.EntryType.PAYMENT_MADE
import com.eggbusinessmanager.domain.ledger.EntryType.PAYMENT_RECEIVED
import com.eggbusinessmanager.domain.ledger.EntryType.SELL
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.payment.PaymentDraft
import com.eggbusinessmanager.domain.report.DashboardCalculator
import com.eggbusinessmanager.domain.report.TradeTotals
import com.eggbusinessmanager.domain.report.TransactionFilter
import com.eggbusinessmanager.domain.report.TransactionSort
import com.eggbusinessmanager.domain.report.TransactionTotals
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.trade.TradeDraft
import com.eggbusinessmanager.domain.usecase.customer.CreateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.CustomerChecks
import com.eggbusinessmanager.domain.usecase.entry.EntryWriter
import com.eggbusinessmanager.domain.usecase.payment.PaymentChecks
import com.eggbusinessmanager.domain.usecase.payment.RecordPaymentUseCase
import com.eggbusinessmanager.domain.usecase.trade.DeleteEntryUseCase
import com.eggbusinessmanager.domain.usecase.trade.EditTradeUseCase
import com.eggbusinessmanager.domain.usecase.trade.RecordTradeUseCase
import com.eggbusinessmanager.domain.usecase.trade.TradeChecks
import com.eggbusinessmanager.testing.FakeAuditRepository
import com.eggbusinessmanager.testing.FakeCatalogRepository
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.BROWN
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.PIECE
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.TRAY
import com.eggbusinessmanager.testing.FakeCustomerRepository
import com.eggbusinessmanager.testing.FakeLedgerRepository
import com.eggbusinessmanager.testing.FakeReportRepository
import com.eggbusinessmanager.testing.FixedClock
import com.eggbusinessmanager.testing.ImmediateTransactionRunner
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

/** Dashboard and Transactions (Phase 6): every figure is derived from entries recorded through the use cases. */
class ReportUseCasesTest {

    private val clock = FixedClock()
    private val today = clock.today()
    private val ledger = FakeLedgerRepository()
    private val customers = FakeCustomerRepository(ledger)
    private val audit = FakeAuditRepository()
    private val tx = ImmediateTransactionRunner
    private val writer = EntryWriter(ledger, audit, tx, clock)
    private val tradeChecks = TradeChecks(customers, FakeCatalogRepository(), clock)
    private val recordTrade = RecordTradeUseCase(tradeChecks, writer)
    private val editTrade = EditTradeUseCase(tradeChecks, ledger, writer)
    private val recordPayment = RecordPaymentUseCase(PaymentChecks(customers, clock), writer)
    private val delete = DeleteEntryUseCase(ledger, audit, tx, clock)
    private val createCustomer = CreateCustomerUseCase(CustomerChecks(customers, clock), customers, ledger, audit, tx, clock)
    private val reports = FakeReportRepository(customers, ledger)
    private val transactions = ObserveTransactionsUseCase(reports)
    private val dashboard = ObserveDashboardUseCase(reports, clock)

    private var requests = 0
    private fun rs(rupees: Long) = Money.rupees(rupees)

    private suspend fun customer(name: String, mobile: String = "", opening: String = ""): Long {
        val draft = CustomerDraft(name = name, mobile = mobile, openingAmount = opening, openingDate = today.minusDays(40))
        return (createCustomer(draft) as DomainResult.Ok).value
    }

    /** [trays] trays at [price] each (30 eggs per tray). */
    private suspend fun trade(id: Long, type: EntryType, trays: String, price: String, date: LocalDate = today): EntryRef {
        clock.advanceBy(1)
        return (recordTrade(TradeDraft(type, id, date, BROWN, TRAY, trays, price), "r${requests++}") as DomainResult.Ok).value
    }

    private suspend fun pay(id: Long, type: EntryType, rupees: String, date: LocalDate = today): EntryRef {
        clock.advanceBy(1)
        return (recordPayment(PaymentDraft(type, id, date, rupees), "r${requests++}") as DomainResult.Ok).value
    }

    private suspend fun list(filter: TransactionFilter = TransactionFilter()) = transactions(filter).first()

    /** Ramesh (98765 43210) buys; Farm Fresh (91234 56789) supplies; Sita does both. */
    private suspend fun sampleBook(): Triple<Long, Long, Long> {
        val ramesh = customer("Ramesh Traders", "9876543210")
        val farm = customer("Farm Fresh", "9123456789")
        val sita = customer("Sita Stores")
        trade(ramesh, SELL, "10", "180", today.minusDays(5)) // 1,800 · 300 eggs
        pay(ramesh, PAYMENT_RECEIVED, "1000", today.minusDays(3))
        trade(farm, BUY, "20", "150", today.minusDays(2)) // 3,000 · 600 eggs
        pay(farm, PAYMENT_MADE, "2000", today.minusDays(1))
        trade(sita, SELL, "2", "200") // 400 · 60 eggs, today
        trade(sita, BUY, "1", "160") // 160 · 30 eggs, today
        return Triple(ramesh, farm, sita)
    }

    // ---- Transactions ----

    @Test
    fun `all transactions are listed newest first with totals, opening balances excluded`() = runTest {
        customer("Old Book", opening = "500")
        sampleBook()
        val result = list()
        assertThat(result.rows.map { it.entry.reference })
            .containsExactly("B-000002", "S-000002", "PC-000001", "B-000001", "RP-000001", "S-000001").inOrder()
        assertThat(result.totals).isEqualTo(
            TransactionTotals(
                count = 6, sales = rs(2_200), purchases = rs(3_160), received = rs(1_000), paid = rs(2_000),
                eggsSold = 360, eggsBought = 630,
            ),
        )
        assertThat(result.totals.netEffect).isEqualTo(rs(2_200 - 3_160 - 1_000 + 2_000))
    }

    @Test
    fun `date range is inclusive and a reversed range means the same days`() = runTest {
        sampleBook()
        val range = TransactionFilter(from = today.minusDays(3), to = today.minusDays(1))
        assertThat(list(range).rows.map { it.entry.reference }).containsExactly("PC-000001", "B-000001", "RP-000001").inOrder()
        assertThat(list(range.copy(from = range.to, to = range.from)).rows).hasSize(3)
        assertThat(list(TransactionFilter(from = today)).rows).hasSize(2)
        assertThat(list(TransactionFilter(to = today.minusDays(5))).rows.single().entry.reference).isEqualTo("S-000001")
    }

    @Test
    fun `customer filter and multiple type selection narrow together`() = runTest {
        val (ramesh, _, sita) = sampleBook()
        assertThat(list(TransactionFilter(customerId = ramesh)).rows.map { it.entry.reference })
            .containsExactly("RP-000001", "S-000001").inOrder()
        assertThat(list(TransactionFilter(types = setOf(SELL, BUY))).totals.count).isEqualTo(4)
        assertThat(list(TransactionFilter(types = setOf(PAYMENT_RECEIVED, PAYMENT_MADE))).totals)
            .isEqualTo(TransactionTotals(count = 2, received = rs(1_000), paid = rs(2_000)))
        assertThat(list(TransactionFilter(types = setOf(SELL, PAYMENT_MADE))).rows.map { it.entry.reference })
            .containsExactly("S-000002", "PC-000001", "S-000001").inOrder()
        assertThat(list(TransactionFilter(customerId = sita, types = setOf(BUY))).rows.single().entry.reference).isEqualTo("B-000002")
        assertThat(list(TransactionFilter(types = emptySet())).rows).isEmpty()
        assertThrows(IllegalArgumentException::class.java) { TransactionFilter(types = setOf(EntryType.OPENING_BALANCE)) }
    }

    @Test
    fun `search matches customer name in any case and mobile digits anywhere`() = runTest {
        sampleBook()
        assertThat(list(TransactionFilter(search = "  ramesh ")).rows.map { it.customerName }.distinct()).containsExactly("Ramesh Traders")
        assertThat(list(TransactionFilter(search = "FRESH")).rows).hasSize(2)
        assertThat(list(TransactionFilter(search = "43210")).rows.map { it.customerName }.distinct()).containsExactly("Ramesh Traders")
        assertThat(list(TransactionFilter(search = "91234 567")).rows.map { it.customerName }.distinct()).containsExactly("Farm Fresh")
        assertThat(list(TransactionFilter(search = "nobody")).rows).isEmpty()
        assertThat(list(TransactionFilter(search = "s")).rows.map { it.customerName }.toSet())
            .containsExactly("Ramesh Traders", "Farm Fresh", "Sita Stores")
    }

    @Test
    fun `every sort order, with newest first breaking ties`() = runTest {
        sampleBook()
        val expected = mapOf(
            TransactionSort.OLDEST to listOf("S-000001", "RP-000001", "B-000001", "PC-000001", "S-000002", "B-000002"),
            TransactionSort.AMOUNT_HIGH to listOf("B-000001", "PC-000001", "S-000001", "RP-000001", "S-000002", "B-000002"),
            TransactionSort.AMOUNT_LOW to listOf("B-000002", "S-000002", "RP-000001", "S-000001", "PC-000001", "B-000001"),
            TransactionSort.CUSTOMER to listOf("PC-000001", "B-000001", "RP-000001", "S-000001", "B-000002", "S-000002"),
        )
        expected.forEach { (sort, refs) ->
            assertThat(list(TransactionFilter(sort = sort)).rows.map { it.entry.reference }).containsExactlyElementsIn(refs).inOrder()
        }
    }

    @Test
    fun `deleted entries disappear and edits show the corrected values`() = runTest {
        val ramesh = customer("Ramesh Traders")
        val sale = trade(ramesh, SELL, "10", "180")
        val receipt = pay(ramesh, PAYMENT_RECEIVED, "500")
        editTrade(sale.rootId, TradeDraft(SELL, ramesh, today, BROWN, PIECE, "100", "6"))
        delete(receipt.rootId)
        val result = list()
        assertThat(result.rows.single().entry.amount).isEqualTo(rs(600))
        assertThat(result.totals).isEqualTo(TransactionTotals(count = 1, sales = rs(600), eggsSold = 100))
    }

    // ---- Dashboard ----

    @Test
    fun `an empty book shows zeros`() = runTest {
        assertThat(dashboard().first()).isEqualTo(com.eggbusinessmanager.domain.report.DashboardSummary())
    }

    @Test
    fun `dashboard totals, today, receivables and payables are derived from the entries`() = runTest {
        val (ramesh, farm, sita) = sampleBook()
        val idle = customer("Idle")
        customers.setActive(idle, active = false, at = clock.now())

        val d = dashboard().first()
        assertThat(d.totalCustomers).isEqualTo(4)
        assertThat(d.activeCustomers).isEqualTo(3)
        assertThat(d.sales).isEqualTo(TradeTotals(count = 2, eggs = 360, value = rs(2_200)))
        assertThat(d.purchases).isEqualTo(TradeTotals(count = 2, eggs = 630, value = rs(3_160)))
        assertThat(d.todaySales).isEqualTo(TradeTotals(1, 60, rs(400)))
        assertThat(d.todayPurchases).isEqualTo(TradeTotals(1, 30, rs(160)))
        // Ramesh owes 800, Sita owes 240; the shop owes Farm Fresh 1,000.
        assertThat(d.receivables).isEqualTo(rs(1_040))
        assertThat(d.receivableCustomers).isEqualTo(2)
        assertThat(d.payables).isEqualTo(rs(1_000))
        assertThat(d.payableCustomers).isEqualTo(1)
        // The receivables and payables are exactly the customers' own balances.
        val balances = listOf(ramesh, farm, sita).map { customers.observe(it).first()!!.netBalance }
        assertThat(balances).containsExactly(rs(800), -rs(1_000), rs(240)).inOrder()
    }

    @Test
    fun `an advance counts under payables and is not netted against other customers`() = runTest {
        val a = customer("Advance Payer")
        val b = customer("Debtor", opening = "700")
        pay(a, PAYMENT_RECEIVED, "300")
        val d = dashboard().first()
        assertThat(d.receivables).isEqualTo(rs(700))
        assertThat(d.payables).isEqualTo(rs(300))
        assertThat(b).isNotEqualTo(a)
    }

    @Test
    fun `recent transactions are the latest recorded, limited, and follow edits and deletes`() = runTest {
        val ramesh = customer("Ramesh Traders", opening = "100")
        val refs = (1..12).map { trade(ramesh, SELL, "1", "$it") }
        var d = dashboard().first()
        assertThat(d.recent).hasSize(DashboardCalculator.RECENT_LIMIT)
        assertThat(d.recent.first().entry.reference).isEqualTo("S-000012")
        assertThat(d.recent.none { it.entry.type == EntryType.OPENING_BALANCE }).isTrue()

        delete(refs.last().rootId)
        clock.advanceBy(1)
        editTrade(refs.first().rootId, TradeDraft(SELL, ramesh, today, BROWN, TRAY, "1", "999"))
        d = dashboard().first()
        assertThat(d.recent.first().entry.reference).isEqualTo("S-000001")
        assertThat(d.recent.map { it.entry.reference }).doesNotContain("S-000012")
        assertThat(d.sales.value).isEqualTo(rs((2..11).sum() + 999L))
    }
}
