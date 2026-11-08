package com.eggbusinessmanager.data.repository

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.data.db.DatabaseFactory
import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.data.db.RoomTransactionRunner
import com.eggbusinessmanager.data.db.entity.LedgerEntryEntity
import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.ledger.Balance
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerCalculator
import com.eggbusinessmanager.domain.ledger.OpeningDirection
import com.eggbusinessmanager.domain.ledger.PaymentMode
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.payment.PaymentDraft
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.trade.TradeDraft
import com.eggbusinessmanager.domain.usecase.customer.CreateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.CustomerChecks
import com.eggbusinessmanager.domain.usecase.customer.DeleteCustomerUseCase
import com.eggbusinessmanager.domain.usecase.entry.EntryWriter
import com.eggbusinessmanager.domain.usecase.payment.EditPaymentUseCase
import com.eggbusinessmanager.domain.usecase.payment.GetPaymentDraftUseCase
import com.eggbusinessmanager.domain.usecase.payment.PaymentChecks
import com.eggbusinessmanager.domain.usecase.payment.PreviewPaymentUseCase
import com.eggbusinessmanager.domain.usecase.payment.RecordPaymentUseCase
import com.eggbusinessmanager.domain.usecase.trade.DeleteEntryUseCase
import com.eggbusinessmanager.domain.usecase.trade.RecordTradeUseCase
import com.eggbusinessmanager.domain.usecase.trade.TradeChecks
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import javax.inject.Provider

/** Payments and the customer ledger on the real encrypted database (US4). */
@RunWith(AndroidJUnit4::class)
class PaymentRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileName = "payment-repo-test.db"
    private lateinit var db: EggBusinessDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var customers: RoomCustomerRepository
    private lateinit var recordTrade: RecordTradeUseCase
    private lateinit var recordPayment: RecordPaymentUseCase
    private lateinit var editPayment: EditPaymentUseCase
    private lateinit var paymentDraft: GetPaymentDraftUseCase
    private lateinit var preview: PreviewPaymentUseCase
    private lateinit var delete: DeleteEntryUseCase
    private lateinit var createCustomer: CreateCustomerUseCase
    private lateinit var deleteCustomer: DeleteCustomerUseCase

    private val today = LocalDate.of(2026, 11, 8)
    private val clock = object : Clock {
        var instant: Instant = Instant.parse("2026-11-08T10:00:00Z")
        override fun now(): Instant = instant.also { instant = instant.plusSeconds(1) }
        override fun today(): LocalDate = today
    }
    private val piece = 1L
    private val brown = 2L

    @Before
    fun setUp() {
        context.deleteDatabase(fileName)
        db = DatabaseFactory.build(context, ByteArray(32).also { SecureRandom().nextBytes(it) }, fileName)
        val provider = Provider { db }
        ledger = RoomLedgerRepository(provider, Dispatchers.IO)
        customers = RoomCustomerRepository(provider, Dispatchers.IO)
        val catalog = RoomCatalogRepository(provider, Dispatchers.IO)
        val audit = RoomAuditRepository(provider, Dispatchers.IO)
        val tx = RoomTransactionRunner(provider)
        val writer = EntryWriter(ledger, audit, tx, clock)
        recordTrade = RecordTradeUseCase(TradeChecks(customers, catalog, clock), writer)
        recordPayment = RecordPaymentUseCase(PaymentChecks(customers, clock), writer)
        editPayment = EditPaymentUseCase(PaymentChecks(customers, clock), ledger, writer)
        paymentDraft = GetPaymentDraftUseCase(ledger)
        preview = PreviewPaymentUseCase(ledger)
        delete = DeleteEntryUseCase(ledger, audit, tx, clock)
        createCustomer = CreateCustomerUseCase(CustomerChecks(customers, clock), customers, ledger, audit, tx, clock)
        deleteCustomer = DeleteCustomerUseCase(customers, ledger, audit, tx, clock)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(fileName)
    }

    private fun rs(rupees: Long) = Money.rupees(rupees)

    private suspend fun customer(name: String, opening: String = "", direction: OpeningDirection = OpeningDirection.CUSTOMER_OWES): Long {
        val draft = CustomerDraft(name = name, openingAmount = opening, openingDirection = direction, openingDate = today.minusDays(10))
        return (createCustomer(draft) as DomainResult.Ok).value
    }

    private suspend fun trade(customerId: Long, type: EntryType, rupees: String) =
        (recordTrade(TradeDraft(type, customerId, today, brown, piece, "1", rupees), "t-" + System.nanoTime()) as DomainResult.Ok).value

    private suspend fun pay(
        customerId: Long,
        type: EntryType,
        rupees: String,
        mode: PaymentMode = PaymentMode.CASH,
        reference: String = "",
    ): EntryRef =
        (recordPayment(PaymentDraft(type, customerId, today, rupees, mode, reference), "p-" + System.nanoTime()) as DomainResult.Ok).value

    /** The SQL aggregate behind the customer list must equal the ledger computed from the same rows. */
    private suspend fun assertListMatchesLedger(customerId: Long) {
        val sqlBalance = checkNotNull(customers.observe(customerId).first()).netBalance
        val view = LedgerCalculator.ledger(ledger.observeEntries(customerId).first())
        assertThat(sqlBalance).isEqualTo(view.balance)
        assertThat(view.totals.balance).isEqualTo(view.balance)
    }

    @Test
    fun specExampleRunningBalancesAndTotals() = runTest {
        val ram = customer("Ram")
        trade(ram, EntryType.SELL, "5000")
        trade(ram, EntryType.BUY, "2000")
        pay(ram, EntryType.PAYMENT_RECEIVED, "1500", PaymentMode.UPI, "UPI-1")
        pay(ram, EntryType.PAYMENT_MADE, "500")

        val view = LedgerCalculator.ledger(ledger.observeEntries(ram).first())
        assertThat(view.lines.map { it.runningBalance }).containsExactly(rs(5_000), rs(3_000), rs(1_500), rs(2_000)).inOrder()
        assertThat(view.lines[2].entry.payment?.mode).isEqualTo(PaymentMode.UPI)
        assertThat(view.lines[2].entry.payment?.reference).isEqualTo("UPI-1")
        assertThat(view.lines.map { it.entry.reference }).containsExactly("S-000001", "B-000001", "RP-000001", "PC-000001").inOrder()
        assertListMatchesLedger(ram)
    }

    @Test
    fun positiveNegativeZeroAndAdvanceBalances() = runTest {
        val owes = customer("Owes", opening = "1000")
        val owed = customer("Owed", opening = "400", direction = OpeningDirection.SHOP_OWES)
        val settled = customer("Settled")
        trade(settled, EntryType.SELL, "750")
        pay(settled, EntryType.PAYMENT_RECEIVED, "750")
        val advance = customer("Advance")
        trade(advance, EntryType.SELL, "2000")
        assertThat(preview(advance, EntryType.PAYMENT_RECEIVED, rs(2_500)).advance).isEqualTo(rs(500))
        pay(advance, EntryType.PAYMENT_RECEIVED, "2500")

        assertThat(Balance.of(customers.observe(owes).first()!!.netBalance)).isEqualTo(Balance.ToReceive(rs(1_000)))
        assertThat(Balance.of(customers.observe(owed).first()!!.netBalance)).isEqualTo(Balance.ToPay(rs(400)))
        assertThat(Balance.of(customers.observe(settled).first()!!.netBalance)).isEqualTo(Balance.Settled)
        assertThat(Balance.of(customers.observe(advance).first()!!.netBalance)).isEqualTo(Balance.ToPay(rs(500)))
        listOf(owes, owed, settled, advance).forEach { assertListMatchesLedger(it) }
    }

    @Test
    fun editingAndDeletingAPaymentRecalculatesAndKeepsHistory() = runTest {
        val ram = customer("Ram")
        trade(ram, EntryType.SELL, "1000")
        val payment = pay(ram, EntryType.PAYMENT_RECEIVED, "300")
        val draft = (paymentDraft(payment.rootId) as DomainResult.Ok).value
        editPayment(payment.rootId, draft.copy(amount = "450", mode = PaymentMode.CHEQUE, reference = "CHQ 1001"), reason = "Typo")

        assertThat(customers.observe(ram).first()!!.netBalance).isEqualTo(rs(550))
        val revisions = ledger.observeRevisions(payment.rootId).first()
        assertThat(revisions.map { it.status }).containsExactly(EntryStatus.SUPERSEDED, EntryStatus.ACTIVE)
        assertThat(revisions.last().payment?.reference).isEqualTo("CHQ 1001")

        delete(payment.rootId, "Bounced")
        assertThat(customers.observe(ram).first()!!.netBalance).isEqualTo(rs(1_000))
        assertThat(LedgerCalculator.ledger(ledger.observeEntries(ram).first()).deleted.single().reference).isEqualTo("RP-000001")
        assertListMatchesLedger(ram)
    }

    @Test
    fun receiveChangedToPayGetsANewReference() = runTest {
        val ram = customer("Ram")
        val receipt = pay(ram, EntryType.PAYMENT_RECEIVED, "200")
        val draft = (paymentDraft(receipt.rootId) as DomainResult.Ok).value
        val changed = (editPayment(receipt.rootId, draft.copy(type = EntryType.PAYMENT_MADE)) as DomainResult.Ok).value
        assertThat(changed.reference).isEqualTo("PC-000001")
        assertThat(customers.observe(ram).first()!!.netBalance).isEqualTo(rs(200))
        assertListMatchesLedger(ram)
    }

    @Test
    fun theSamePaymentRequestIsStoredOnceAndBlocksCustomerDeletion() = runTest {
        val ram = customer("Ram")
        val draft = PaymentDraft(EntryType.PAYMENT_RECEIVED, ram, today, "100")
        val first = recordPayment(draft, "same")
        assertThat(recordPayment(draft, "same")).isEqualTo(first)
        assertThat(ledger.observeEntries(ram).first()).hasSize(1)
        assertThat(deleteCustomer(ram)).isInstanceOf(DomainResult.Err::class.java)
    }

    @Test
    fun triggersRejectInconsistentPaymentRows() = runTest {
        val ram = customer("Ram")
        val dao = db.ledgerEntryDao()
        fun row(
            mode: String?,
            effect: Long = -10_000,
            reference: String? = null,
            type: String = "PAYMENT_RECEIVED",
            variety: Long? = null,
        ) = LedgerEntryEntity(
            rootEntryId = 0, entryType = type, referenceNo = "RP-T", status = "ACTIVE", customerId = ram,
            entryDate = today.toEpochDay(), amountPaise = 10_000, balanceEffectPaise = effect, paymentMode = mode,
            paymentReference = reference, varietyId = variety, createdAt = 0,
        )
        listOf(
            row(mode = null), // a payment needs a mode
            row(mode = "BARTER"), // unknown mode
            row(mode = "CASH", effect = 10_000), // wrong sign for a receipt
            row(mode = "CASH", reference = "r".repeat(51)), // reference too long
            row(mode = "CASH", variety = brown), // no trade columns on payments
            row(mode = null, effect = 10_000, reference = "x", type = "OPENING_BALANCE"), // payment fields only on payments
        ).forEach { bad -> assertThrows(SQLiteConstraintException::class.java) { runBlocking { dao.insert(bad) } } }

        val good = dao.insert(row(mode = "UPI", reference = "ok"))
        val sql = db.openHelper.writableDatabase
        listOf("payment_mode = 'CASH'", "payment_reference = 'z'").forEach { change ->
            assertThrows(SQLiteConstraintException::class.java) { sql.execSQL("UPDATE ledger_entry SET $change WHERE id = $good") }
        }
    }
}
