package com.eggbusinessmanager.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.data.db.DatabaseFactory
import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.data.db.RoomTransactionRunner
import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.payment.PaymentDraft
import com.eggbusinessmanager.domain.report.DashboardCalculator
import com.eggbusinessmanager.domain.report.TRANSACTION_TYPES
import com.eggbusinessmanager.domain.report.TransactionFilter
import com.eggbusinessmanager.domain.report.TransactionRow
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.trade.TradeDraft
import com.eggbusinessmanager.domain.usecase.customer.CreateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.CustomerChecks
import com.eggbusinessmanager.domain.usecase.entry.EntryWriter
import com.eggbusinessmanager.domain.usecase.payment.PaymentChecks
import com.eggbusinessmanager.domain.usecase.payment.RecordPaymentUseCase
import com.eggbusinessmanager.domain.usecase.report.ObserveTransactionsUseCase
import com.eggbusinessmanager.domain.usecase.trade.DeleteEntryUseCase
import com.eggbusinessmanager.domain.usecase.trade.EditTradeUseCase
import com.eggbusinessmanager.domain.usecase.trade.RecordTradeUseCase
import com.eggbusinessmanager.domain.usecase.trade.TradeChecks
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import javax.inject.Provider
import kotlin.random.Random

/**
 * Dashboard and Transactions SQL on the real encrypted database (Phase 6). The SQL must agree exactly
 * with the domain's reference definitions (`TransactionFilter.matches`, `DashboardCalculator`) over the
 * same rows, including deleted and edited entries, deactivated customers and LIKE special characters.
 */
@RunWith(AndroidJUnit4::class)
class ReportRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileName = "report-repo-test.db"
    private lateinit var db: EggBusinessDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var customers: RoomCustomerRepository
    private lateinit var reports: RoomReportRepository
    private lateinit var recordTrade: RecordTradeUseCase
    private lateinit var editTrade: EditTradeUseCase
    private lateinit var recordPayment: RecordPaymentUseCase
    private lateinit var delete: DeleteEntryUseCase
    private lateinit var createCustomer: CreateCustomerUseCase

    private val today = LocalDate.of(2026, 11, 8)
    private val clock = object : Clock {
        var instant: Instant = Instant.parse("2026-11-08T10:00:00Z")
        override fun now(): Instant = instant.also { instant = instant.plusSeconds(1) }
        override fun today(): LocalDate = today
    }
    private val tray = 3L
    private val brown = 2L
    private val ids = mutableListOf<Long>()

    @Before
    fun setUp() {
        context.deleteDatabase(fileName)
        db = DatabaseFactory.build(context, ByteArray(32).also { SecureRandom().nextBytes(it) }, fileName)
        val provider = Provider { db }
        ledger = RoomLedgerRepository(provider, Dispatchers.IO)
        customers = RoomCustomerRepository(provider, Dispatchers.IO)
        reports = RoomReportRepository(provider, Dispatchers.IO)
        val catalog = RoomCatalogRepository(provider, Dispatchers.IO)
        val audit = RoomAuditRepository(provider, Dispatchers.IO)
        val tx = RoomTransactionRunner(provider)
        val writer = EntryWriter(ledger, audit, tx, clock)
        val tradeChecks = TradeChecks(customers, catalog, clock)
        recordTrade = RecordTradeUseCase(tradeChecks, writer)
        editTrade = EditTradeUseCase(tradeChecks, ledger, writer)
        recordPayment = RecordPaymentUseCase(PaymentChecks(customers, clock), writer)
        delete = DeleteEntryUseCase(ledger, audit, tx, clock)
        createCustomer = CreateCustomerUseCase(CustomerChecks(customers, clock), customers, ledger, audit, tx, clock)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(fileName)
    }

    private suspend fun customer(name: String, mobile: String = "", opening: String = ""): Long {
        val draft = CustomerDraft(name = name, mobile = mobile, openingAmount = opening, openingDate = today.minusDays(90))
        return (createCustomer(draft) as DomainResult.Ok).value.also { ids += it }
    }

    /** A random book: trades and payments over 60 days, then some deletes and edits. */
    private suspend fun randomBook(random: Random) {
        val people = listOf(
            customer("Ramesh Traders", "9876543210", opening = "1200"),
            customer("Farm Fresh", "9123456789"),
            customer("Sita Stores"),
            customer("A_B 100% Eggs", "9000000001"),
            customer("Dormant", "9000000002"),
        )
        val refs = mutableListOf<EntryRef>()
        repeat(120) {
            val id = people.random(random)
            val date = today.minusDays(random.nextLong(0, 60))
            val rupees = "${random.nextInt(1, 5_000)}.${random.nextInt(0, 100).toString().padStart(2, '0')}"
            refs += when (val type = TRANSACTION_TYPES.random(random)) {
                EntryType.SELL, EntryType.BUY -> {
                    val draft = TradeDraft(type, id, date, brown, tray, random.nextInt(1, 40).toString(), rupees)
                    (recordTrade(draft, "t$it") as DomainResult.Ok).value
                }
                else -> (recordPayment(PaymentDraft(type, id, date, rupees), "p$it") as DomainResult.Ok).value
            }
        }
        refs.shuffled(random).take(15).forEach { delete(it.rootId) }
        // Edit a few remaining sales: new revisions must replace the old ones everywhere.
        refs.filter { it.reference.startsWith("S-") }.takeLast(5).forEach { ref ->
            val current = ledger.currentRevision(ref.rootId) ?: return@forEach
            if (current.status.name == "ACTIVE") {
                editTrade(ref.rootId, TradeDraft(EntryType.SELL, current.customerId, current.date, brown, tray, "3", "99"))
            }
        }
        customers.setActive(people.last(), active = false, at = clock.now())
    }

    private suspend fun allEntries(): List<LedgerEntry> = ids.flatMap { ledger.observeEntries(it).first() }

    private suspend fun allRows(): List<TransactionRow> = allEntries().map { e ->
        val c = checkNotNull(customers.get(e.customerId))
        TransactionRow(e, c.name, c.mobile)
    }

    @Test
    fun transactionFiltersMatchTheReferenceDefinition() = runTest {
        randomBook(Random(20261108))
        val rows = allRows()
        val filters = listOf(
            TransactionFilter(),
            TransactionFilter(from = today.minusDays(20), to = today.minusDays(5)),
            TransactionFilter(from = today),
            TransactionFilter(to = today.minusDays(30)),
            TransactionFilter(customerId = ids[1]),
            TransactionFilter(types = setOf(EntryType.SELL)),
            TransactionFilter(types = setOf(EntryType.PAYMENT_RECEIVED, EntryType.PAYMENT_MADE)),
            TransactionFilter(types = setOf(EntryType.BUY, EntryType.PAYMENT_MADE), customerId = ids[0]),
            TransactionFilter(types = emptySet()),
            TransactionFilter(search = "fresh"),
            TransactionFilter(search = "RAMESH"),
            TransactionFilter(search = "43210"),
            TransactionFilter(search = "_"), // must match the literal underscore only
            TransactionFilter(search = "100%"),
            TransactionFilter(search = "s", from = today.minusDays(10), types = setOf(EntryType.SELL, EntryType.BUY)),
        )
        filters.forEach { filter ->
            val sql = reports.observeTransactions(filter).first().sortedBy { it.entry.id }
            val expected = rows.filter(filter::matches).sortedBy { it.entry.id }
            assertThat(sql).isEqualTo(expected)
        }
        assertThat(reports.observeTransactions(TransactionFilter(search = "_")).first().map { it.customerName }.distinct())
            .containsExactly("A_B 100% Eggs")
    }

    @Test
    fun dashboardMatchesTheReferenceCalculationAndCustomerBalances() = runTest {
        randomBook(Random(7))
        val everyone = ids.map { checkNotNull(customers.get(it)) }
        val expected = DashboardCalculator.of(everyone, allEntries(), today, today, recentLimit = 10)
        val actual = reports.observeDashboard(today, today, recentLimit = 10).first()
        assertThat(actual).isEqualTo(expected)

        // Receivables/payables are exactly the positive/negative customer-list balances.
        val balances = ids.map { checkNotNull(customers.observe(it).first()).netBalance }
        assertThat(actual.receivables).isEqualTo(balances.filter { it.isPositive }.fold(Money.ZERO, Money::plus))
        assertThat(actual.payables).isEqualTo(balances.filter { it.isNegative }.fold(Money.ZERO) { s, b -> s - b })
        assertThat(actual.totalCustomers).isEqualTo(5)
        assertThat(actual.activeCustomers).isEqualTo(4)
    }

    @Test
    fun dashboardUpdatesLiveAndFilteredTotalsFollow() = runTest {
        assertThat(reports.observeDashboard(today, today, 10).first().sales.value).isEqualTo(Money.ZERO)
        val ram = customer("Ram")
        val sale = (recordTrade(TradeDraft(EntryType.SELL, ram, today, brown, tray, "10", "180"), "a") as DomainResult.Ok).value
        recordPayment(PaymentDraft(EntryType.PAYMENT_RECEIVED, ram, today, "500"), "b")

        var d = reports.observeDashboard(today, today, 10).first()
        assertThat(d.periodSales.value).isEqualTo(Money.rupees(1_800))
        assertThat(d.periodSales.eggs).isEqualTo(300)
        assertThat(d.receivables).isEqualTo(Money.rupees(1_300))
        assertThat(d.recent.map { it.entry.reference }).containsExactly("RP-000001", "S-000001").inOrder()

        delete(sale.rootId)
        d = reports.observeDashboard(today, today, 10).first()
        assertThat(d.periodSales.count).isEqualTo(0)
        assertThat(d.payables).isEqualTo(Money.rupees(500))

        val listed = ObserveTransactionsUseCase(reports)(TransactionFilter()).first()
        assertThat(listed.totals.count).isEqualTo(1)
        assertThat(listed.totals.received).isEqualTo(Money.rupees(500))
    }
}
