package com.eggbusinessmanager.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.data.db.DatabaseFactory
import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.data.db.RoomTransactionRunner
import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.EntryValues
import com.eggbusinessmanager.domain.ledger.PaymentMode
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.repository.LedgerRepository
import com.eggbusinessmanager.domain.trade.PaidNow
import com.eggbusinessmanager.domain.trade.TradeDraft
import com.eggbusinessmanager.domain.usecase.customer.CreateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.CustomerChecks
import com.eggbusinessmanager.domain.usecase.entry.EntryWriter
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

/** FR-023 on the real encrypted database: a trade and its on-the-spot payment are saved both or neither. */
@RunWith(AndroidJUnit4::class)
class PaidNowRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileName = "paid-now-test.db"
    private lateinit var db: EggBusinessDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var customers: RoomCustomerRepository
    private lateinit var audit: RoomAuditRepository
    private lateinit var tx: RoomTransactionRunner
    private lateinit var checks: TradeChecks
    private lateinit var createCustomer: CreateCustomerUseCase

    private val today = LocalDate.of(2026, 11, 8)
    private val clock = object : Clock {
        var instant: Instant = Instant.parse("2026-11-08T10:00:00Z")
        override fun now(): Instant = instant.also { instant = instant.plusSeconds(1) }
        override fun today(): LocalDate = today
    }

    @Before
    fun setUp() {
        context.deleteDatabase(fileName)
        db = DatabaseFactory.build(context, ByteArray(32).also { SecureRandom().nextBytes(it) }, fileName)
        val provider = Provider { db }
        ledger = RoomLedgerRepository(provider, Dispatchers.IO)
        customers = RoomCustomerRepository(provider, Dispatchers.IO)
        audit = RoomAuditRepository(provider, Dispatchers.IO)
        tx = RoomTransactionRunner(provider)
        checks = TradeChecks(customers, RoomCatalogRepository(provider, Dispatchers.IO), clock)
        createCustomer = CreateCustomerUseCase(CustomerChecks(customers, clock), customers, ledger, audit, tx, clock)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(fileName)
    }

    private fun record(repo: LedgerRepository = ledger) = RecordTradeUseCase(checks, EntryWriter(repo, audit, tx, clock))

    private suspend fun customer(name: String) = (createCustomer(CustomerDraft(name = name)) as DomainResult.Ok).value

    /** 10 trays (seeded id 3) of Brown (id 2) at ₹180 = ₹1,800. */
    private fun sale(customerId: Long, paid: String) =
        TradeDraft(EntryType.SELL, customerId, today, 2L, 3L, "10", "180", paidNow = PaidNow(paid, PaymentMode.UPI))

    private suspend fun sqlBalance(customerId: Long) = checkNotNull(customers.observe(customerId).first()).netBalance

    private suspend fun rows(customerId: Long) = ledger.observeEntries(customerId).first()

    @Test
    fun saleAndPaymentAreStoredLinkedAndTheSqlBalanceAgrees() = runTest {
        val ram = customer("Ram")

        val ref = (record()(sale(ram, "1000"), "req-1") as DomainResult.Ok).value

        assertThat(ref.reference).isEqualTo("S-000001")
        assertThat(ref.linkedReference).isEqualTo("RP-000001")
        assertThat(sqlBalance(ram)).isEqualTo(Money.rupees(800))
        val (trade, payment) = rows(ram).sortedBy { it.id }
        assertThat(payment.type).isEqualTo(EntryType.PAYMENT_RECEIVED)
        assertThat(payment.payment?.mode).isEqualTo(PaymentMode.UPI)
        assertThat(trade.linkedRootId).isEqualTo(payment.rootId)
        assertThat(payment.linkedRootId).isEqualTo(trade.rootId)
        // A repeat with the same request id is the same save.
        assertThat((record()(sale(ram, "1000"), "req-1") as DomainResult.Ok).value).isEqualTo(ref)
        assertThat(rows(ram)).hasSize(2)
    }

    @Test
    fun aFailureWhileSavingThePaymentLeavesNothingBehind() = runTest {
        val ram = customer("Ram")
        // Fails on the second insert of the save: the payment, after the sale went in.
        val failing = object : LedgerRepository by ledger {
            var inserts = 0
            override suspend fun insertEntry(values: EntryValues, clientRequestId: String?, at: Instant): EntryRef {
                check(++inserts < 2) { "injected failure" }
                return ledger.insertEntry(values, clientRequestId, at)
            }
        }

        val result = record(failing)(sale(ram, "1000"), "req-fail")

        assertThat(result).isInstanceOf(DomainResult.Err::class.java)
        assertThat(rows(ram)).isEmpty()
        assertThat(sqlBalance(ram)).isEqualTo(Money.ZERO)
        // The reference numbers used by the rolled-back save are free again: nothing was ever committed.
        assertThat((record()(sale(ram, "1000"), "req-2") as DomainResult.Ok).value.reference).isEqualTo("S-000001")
    }

    @Test
    fun editsKeepTheLinkAndDeletingBothRestoresTheBalance() = runTest {
        val ram = customer("Ram")
        val writer = EntryWriter(ledger, audit, tx, clock)
        val ref = (record()(sale(ram, "1000"), "req-1") as DomainResult.Ok).value

        EditTradeUseCase(checks, ledger, writer)(ref.rootId, sale(ram, "").copy(quantity = "20"))
        assertThat(ledger.currentRevision(ref.rootId)!!.linkedRootId).isNotNull()
        assertThat(sqlBalance(ram)).isEqualTo(Money.rupees(2_600))

        DeleteEntryUseCase(ledger, audit, tx, clock)(ref.rootId, alsoLinked = true)
        assertThat(rows(ram).filter { it.status == EntryStatus.ACTIVE }).isEmpty()
        assertThat(sqlBalance(ram)).isEqualTo(Money.ZERO)
    }
}
