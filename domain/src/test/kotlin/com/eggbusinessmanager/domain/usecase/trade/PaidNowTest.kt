package com.eggbusinessmanager.domain.usecase.trade

import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.PaymentMode
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.trade.PaidNow
import com.eggbusinessmanager.domain.trade.TradeDraft
import com.eggbusinessmanager.domain.usecase.customer.CreateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.CustomerChecks
import com.eggbusinessmanager.domain.usecase.customer.ObserveCustomerLedgerUseCase
import com.eggbusinessmanager.domain.usecase.entry.EntryWriter
import com.eggbusinessmanager.testing.FakeAuditRepository
import com.eggbusinessmanager.testing.FakeCatalogRepository
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.BROWN
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.TRAY
import com.eggbusinessmanager.testing.FakeCustomerRepository
import com.eggbusinessmanager.testing.FakeLedgerRepository
import com.eggbusinessmanager.testing.FixedClock
import com.eggbusinessmanager.testing.ImmediateTransactionRunner
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** FR-023 / BR-013: a sale or purchase with the amount paid on the spot, saved together and linked. */
class PaidNowTest {

    private val clock = FixedClock()
    private val today = clock.today()
    private val ledger = FakeLedgerRepository()
    private val customers = FakeCustomerRepository(ledger)
    private val audit = FakeAuditRepository()
    private val tx = ImmediateTransactionRunner
    private val checks = TradeChecks(customers, FakeCatalogRepository(), clock)
    private val writer = EntryWriter(ledger, audit, tx, clock)
    private val record = RecordTradeUseCase(checks, writer)
    private val edit = EditTradeUseCase(checks, ledger, writer)
    private val delete = DeleteEntryUseCase(ledger, audit, tx, clock)
    private val observe = ObserveEntryUseCase(ledger)
    private val history = ObserveCustomerLedgerUseCase(ledger)
    private val createCustomer = CreateCustomerUseCase(CustomerChecks(customers, clock), customers, ledger, audit, tx, clock)

    private suspend fun customer(name: String): Long =
        (createCustomer(CustomerDraft(name = name, openingDate = today)) as DomainResult.Ok).value

    /** 10 trays at ₹180 = ₹1,800. */
    private fun trade(customerId: Long, type: EntryType = EntryType.SELL, paid: String = "", mode: PaymentMode = PaymentMode.CASH) =
        TradeDraft(type, customerId, today, BROWN, TRAY, "10", "180", paidNow = PaidNow(paid, mode))

    private suspend fun recorded(draft: TradeDraft, request: String = "r" + Math.random()): EntryRef =
        (record(draft, request) as DomainResult.Ok).value

    private suspend fun balance(customerId: Long) = history(customerId).first().balance

    @Test
    fun `a sale with money received now saves both lines, linked, and the customer owes the rest`() = runTest {
        val ram = customer("Ram")
        val before = audit.events.size

        val ref = recorded(trade(ram, paid = "1000", mode = PaymentMode.UPI))

        assertThat(ref.reference).isEqualTo("S-000001")
        assertThat(ref.linkedReference).isEqualTo("RP-000001")
        assertThat(balance(ram)).isEqualTo(Money.rupees(800))
        val lines = history(ram).first().lines.map { it.entry }
        assertThat(lines.map { it.type }).containsExactly(EntryType.SELL, EntryType.PAYMENT_RECEIVED)
        val payment = lines.last()
        assertThat(payment.date).isEqualTo(today)
        assertThat(payment.amount).isEqualTo(Money.rupees(1_000))
        assertThat(payment.payment?.mode).isEqualTo(PaymentMode.UPI)
        assertThat(payment.linkedRootId).isEqualTo(ref.rootId)
        assertThat(lines.first().linkedRootId).isEqualTo(payment.rootId)
        assertThat(observe(ref.rootId).first()!!.linked?.reference).isEqualTo("RP-000001")
        assertThat(observe(payment.rootId).first()!!.linked?.reference).isEqualTo("S-000001")
        assertThat(audit.events.size - before).isEqualTo(2) // one audit row each
    }

    @Test
    fun `a purchase paid in full now leaves nothing to pay`() = runTest {
        val farm = customer("Farm")
        val ref = recorded(trade(farm, EntryType.BUY, paid = "1800"))
        assertThat(ref.reference).isEqualTo("B-000001")
        assertThat(ref.linkedReference).isEqualTo("PC-000001")
        assertThat(balance(farm)).isEqualTo(Money.ZERO)
    }

    @Test
    fun `blank or zero saves the trade alone`() = runTest {
        val ram = customer("Ram")
        assertThat(recorded(trade(ram)).linkedReference).isNull()
        assertThat(recorded(trade(ram, paid = "0")).linkedReference).isNull()
        assertThat(history(ram).first().lines.map { it.entry.type }).containsExactly(EntryType.SELL, EntryType.SELL)
    }

    @Test
    fun `more than the total or a bad amount is rejected with nothing saved`() = runTest {
        val ram = customer("Ram")
        for ((paid, code) in listOf("1800.01" to ValidationCode.OUT_OF_RANGE, "12.345" to ValidationCode.INVALID_FORMAT)) {
            val result = record(trade(ram, paid = paid), "r-" + paid)
            assertThat(((result as DomainResult.Err).error as DomainError.Validation).fields).containsExactly(Field.PAID_NOW, code)
        }
        assertThat(history(ram).first().lines).isEmpty()
    }

    @Test
    fun `saving twice with the same request id keeps one sale and one payment`() = runTest {
        val ram = customer("Ram")
        val first = recorded(trade(ram, paid = "500"), "same")
        val second = recorded(trade(ram, paid = "500"), "same")
        assertThat(second).isEqualTo(first)
        assertThat(history(ram).first().lines).hasSize(2)
    }

    @Test
    fun `deleting the sale can take its payment along, or leave it`() = runTest {
        val ram = customer("Ram")
        val both = recorded(trade(ram, paid = "1000"))
        assertThat(delete(both.rootId, alsoLinked = true)).isEqualTo(DomainResult.Ok(Unit))
        assertThat(balance(ram)).isEqualTo(Money.ZERO)
        assertThat(observe(both.rootId).first()!!.linked?.status).isEqualTo(EntryStatus.VOIDED)

        val saleOnly = recorded(trade(ram, paid = "1000"))
        delete(saleOnly.rootId)
        assertThat(balance(ram)).isEqualTo(Money.rupees(-1_000)) // the ₹1,000 received stays, as an advance
    }

    @Test
    fun `editing the sale keeps the link, also through a type change`() = runTest {
        val ram = customer("Ram")
        val ref = recorded(trade(ram, paid = "1000"))
        edit(ref.rootId, trade(ram).copy(quantity = "20"))
        assertThat(observe(ref.rootId).first()!!.linked?.reference).isEqualTo("RP-000001")
        assertThat(balance(ram)).isEqualTo(Money.rupees(2_600))

        val changed = (edit(ref.rootId, trade(ram, EntryType.BUY)) as DomainResult.Ok).value
        assertThat(changed.reference).isEqualTo("B-000001")
        val paymentRoot = observe(changed.rootId).first()!!.linked!!.rootId
        assertThat(observe(paymentRoot).first()!!.linked?.reference).isEqualTo("B-000001")
    }
}
