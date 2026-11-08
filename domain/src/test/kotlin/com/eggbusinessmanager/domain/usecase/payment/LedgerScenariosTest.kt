package com.eggbusinessmanager.domain.usecase.payment

import com.eggbusinessmanager.domain.customer.AuditEvent
import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.ledger.Balance
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.EntryType.BUY
import com.eggbusinessmanager.domain.ledger.EntryType.PAYMENT_MADE
import com.eggbusinessmanager.domain.ledger.EntryType.PAYMENT_RECEIVED
import com.eggbusinessmanager.domain.ledger.EntryType.SELL
import com.eggbusinessmanager.domain.ledger.LedgerCalculator
import com.eggbusinessmanager.domain.ledger.LedgerTotals
import com.eggbusinessmanager.domain.ledger.OpeningDirection
import com.eggbusinessmanager.domain.ledger.PaymentMode
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.payment.PaymentDraft
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.trade.TradeDraft
import com.eggbusinessmanager.domain.usecase.customer.CreateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.CustomerChecks
import com.eggbusinessmanager.domain.usecase.customer.ObserveCustomerLedgerUseCase
import com.eggbusinessmanager.domain.usecase.customer.ObserveCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.UpdateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.entry.EntryWriter
import com.eggbusinessmanager.domain.usecase.trade.DeleteEntryUseCase
import com.eggbusinessmanager.domain.usecase.trade.EditTradeUseCase
import com.eggbusinessmanager.domain.usecase.trade.RecordTradeUseCase
import com.eggbusinessmanager.domain.usecase.trade.TradeChecks
import com.eggbusinessmanager.testing.FakeAuditRepository
import com.eggbusinessmanager.testing.FakeCatalogRepository
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.BROWN
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.PIECE
import com.eggbusinessmanager.testing.FakeCustomerRepository
import com.eggbusinessmanager.testing.FakeLedgerRepository
import com.eggbusinessmanager.testing.FixedClock
import com.eggbusinessmanager.testing.ImmediateTransactionRunner
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * The customer ledger with trades and payments (US4): balance formula, running and closing balance,
 * totals, advances, and corrections of historical entries. Every figure is computed from entries;
 * nothing is seeded with a balance.
 */
class LedgerScenariosTest {

    private val clock = FixedClock()
    private val today = clock.today()
    private val ledger = FakeLedgerRepository()
    private val customers = FakeCustomerRepository(ledger)
    private val catalog = FakeCatalogRepository()
    private val audit = FakeAuditRepository()
    private val tx = ImmediateTransactionRunner
    private val writer = EntryWriter(ledger, audit, tx, clock)

    private val recordTrade = RecordTradeUseCase(TradeChecks(customers, catalog, clock), writer)
    private val editTrade = EditTradeUseCase(TradeChecks(customers, catalog, clock), ledger, writer)
    private val recordPayment = RecordPaymentUseCase(PaymentChecks(customers, clock), writer)
    private val editPayment = EditPaymentUseCase(PaymentChecks(customers, clock), ledger, writer)
    private val paymentDraftOf = GetPaymentDraftUseCase(ledger)
    private val preview = PreviewPaymentUseCase(ledger)
    private val delete = DeleteEntryUseCase(ledger, audit, tx, clock)
    private val observeLedger = ObserveCustomerLedgerUseCase(ledger)
    private val observeCustomer = ObserveCustomerUseCase(customers)
    private val createCustomer = CreateCustomerUseCase(CustomerChecks(customers, clock), customers, ledger, audit, tx, clock)
    private val updateCustomer = UpdateCustomerUseCase(CustomerChecks(customers, clock), customers, ledger, audit, tx, clock)

    private var requests = 0

    private suspend fun customer(
        name: String = "Ramesh",
        opening: String = "",
        direction: OpeningDirection = OpeningDirection.CUSTOMER_OWES,
    ): Long {
        val draft = CustomerDraft(name = name, openingAmount = opening, openingDirection = direction, openingDate = today.minusDays(30))
        return (createCustomer(draft) as DomainResult.Ok).value
    }

    /** A trade of 1 piece at [rupees], so the total is exactly [rupees]. */
    private suspend fun trade(customerId: Long, type: EntryType, rupees: String, date: LocalDate = today): EntryRef {
        val draft = TradeDraft(type, customerId, date, BROWN, PIECE, quantity = "1", price = rupees)
        return (recordTrade(draft, "r${requests++}") as DomainResult.Ok).value
    }

    private suspend fun pay(
        customerId: Long,
        type: EntryType,
        rupees: String,
        date: LocalDate = today,
        mode: PaymentMode = PaymentMode.CASH,
    ): EntryRef {
        val draft = PaymentDraft(type, customerId, date, rupees, mode)
        return (recordPayment(draft, "r${requests++}") as DomainResult.Ok).value
    }

    private suspend fun ledgerOf(customerId: Long) = observeLedger(customerId).first()
    private suspend fun balance(customerId: Long) = ledgerOf(customerId).balance
    private fun rs(rupees: Long, paise: Long = 0) = Money.rupees(rupees, paise)

    /** The SQL-style aggregate (customer list) and the ledger must agree, and both must equal the formula. */
    private suspend fun assertConsistent(customerId: Long) {
        val view = ledgerOf(customerId)
        val listBalance = checkNotNull(observeCustomer(customerId).first()).netBalance
        assertThat(view.closingBalance).isEqualTo(view.totals.balance)
        assertThat(listBalance).isEqualTo(view.balance)
        assertThat(view.lines.lastOrNull()?.runningBalance ?: Money.ZERO).isEqualTo(view.balance)
    }

    @Test
    fun `spec independent test - 5000 sale, 2000 purchase, 1500 received, 500 paid`() = runTest {
        val ram = customer()
        trade(ram, SELL, "5000")
        trade(ram, BUY, "2000")
        pay(ram, PAYMENT_RECEIVED, "1500")
        pay(ram, PAYMENT_MADE, "500")

        val view = ledgerOf(ram)
        assertThat(view.lines.map { it.runningBalance }).containsExactly(rs(5_000), rs(3_000), rs(1_500), rs(2_000)).inOrder()
        assertThat(Balance.of(view.balance)).isEqualTo(Balance.ToReceive(rs(2_000)))
        assertThat(view.totals).isEqualTo(LedgerTotals(Money.ZERO, rs(5_000), rs(2_000), rs(1_500), rs(500)))
        assertConsistent(ram)
    }

    @Test
    fun `payment references use their own series`() = runTest {
        val ram = customer()
        assertThat(pay(ram, PAYMENT_RECEIVED, "10").reference).isEqualTo("RP-000001")
        assertThat(pay(ram, PAYMENT_MADE, "10").reference).isEqualTo("PC-000001")
        assertThat(pay(ram, PAYMENT_RECEIVED, "10").reference).isEqualTo("RP-000002")
    }

    @Test
    fun `positive balance - customer owes the shop`() = runTest {
        val ram = customer(opening = "1200")
        trade(ram, SELL, "800")
        assertThat(Balance.of(balance(ram))).isEqualTo(Balance.ToReceive(rs(2_000)))
        assertConsistent(ram)
    }

    @Test
    fun `negative balance - shop owes the customer`() = runTest {
        val farm = customer("Farm", opening = "300", direction = OpeningDirection.SHOP_OWES)
        trade(farm, BUY, "1700")
        assertThat(Balance.of(balance(farm))).isEqualTo(Balance.ToPay(rs(2_000)))
        assertConsistent(farm)
    }

    @Test
    fun `zero balance - settled by an exact payment (US4-1 and US4-3)`() = runTest {
        val ram = customer()
        trade(ram, SELL, "2000")
        pay(ram, PAYMENT_RECEIVED, "2000")
        assertThat(Balance.of(balance(ram))).isEqualTo(Balance.Settled)

        val farm = customer("Farm")
        trade(farm, BUY, "3000")
        pay(farm, PAYMENT_MADE, "3000")
        assertThat(Balance.of(balance(farm))).isEqualTo(Balance.Settled)
        assertConsistent(ram)
        assertConsistent(farm)
    }

    @Test
    fun `US4-2 overpayment is previewed as an advance and becomes To pay`() = runTest {
        val ram = customer()
        trade(ram, SELL, "2000")

        val impact = preview(ram, PAYMENT_RECEIVED, rs(2_500))
        assertThat(impact.outstanding).isEqualTo(rs(2_000))
        assertThat(impact.advance).isEqualTo(rs(500))

        pay(ram, PAYMENT_RECEIVED, "2500")
        assertThat(Balance.of(balance(ram))).isEqualTo(Balance.ToPay(rs(500)))
        assertThat(balance(ram)).isEqualTo(impact.resultingBalance)
    }

    @Test
    fun `US4-3a advances are used up by later trades through the running balance`() = runTest {
        val ram = customer()
        pay(ram, PAYMENT_RECEIVED, "5000")
        assertThat(Balance.of(balance(ram))).isEqualTo(Balance.ToPay(rs(5_000)))
        trade(ram, SELL, "3000")
        assertThat(Balance.of(balance(ram))).isEqualTo(Balance.ToPay(rs(2_000)))

        val supplier = customer("Supplier")
        pay(supplier, PAYMENT_MADE, "4000")
        assertThat(Balance.of(balance(supplier))).isEqualTo(Balance.ToReceive(rs(4_000)))
        trade(supplier, BUY, "4000")
        assertThat(Balance.of(balance(supplier))).isEqualTo(Balance.Settled)
        assertConsistent(ram)
        assertConsistent(supplier)
    }

    @Test
    fun `US4-6 a back-dated entry takes its date position and later running balances follow`() = runTest {
        val ram = customer()
        trade(ram, SELL, "1000", date = today.minusDays(2))
        pay(ram, PAYMENT_RECEIVED, "400", date = today)
        trade(ram, SELL, "250", date = today.minusDays(1)) // entered last, dated in between

        val view = ledgerOf(ram)
        assertThat(view.lines.map { it.entry.amount }).containsExactly(rs(1_000), rs(250), rs(400)).inOrder()
        assertThat(view.lines.map { it.runningBalance }).containsExactly(rs(1_000), rs(1_250), rs(850)).inOrder()
    }

    @Test
    fun `editing a historical payment recalculates every later running balance and the closing balance`() = runTest {
        val ram = customer()
        trade(ram, SELL, "1000", date = today.minusDays(3))
        val payment = pay(ram, PAYMENT_RECEIVED, "300", date = today.minusDays(2))
        trade(ram, SELL, "500", date = today.minusDays(1))
        assertThat(ledgerOf(ram).lines.map { it.runningBalance }).containsExactly(rs(1_000), rs(700), rs(1_200)).inOrder()

        val draft = (paymentDraftOf(payment.rootId) as DomainResult.Ok).value
        val edited = editPayment(payment.rootId, draft.copy(amount = "600", mode = PaymentMode.UPI, reference = "UPI-77"))
        assertThat(edited).isEqualTo(DomainResult.Ok(payment))

        val view = ledgerOf(ram)
        assertThat(view.lines.map { it.runningBalance }).containsExactly(rs(1_000), rs(400), rs(900)).inOrder()
        assertThat(view.lines[1].entry.payment?.reference).isEqualTo("UPI-77")
        assertThat(view.totals.received).isEqualTo(rs(600))
        assertConsistent(ram)
    }

    @Test
    fun `editing a historical sale recalculates the balance`() = runTest {
        val ram = customer()
        val sale = trade(ram, SELL, "1000", date = today.minusDays(5))
        pay(ram, PAYMENT_RECEIVED, "1000")
        assertThat(balance(ram)).isEqualTo(Money.ZERO)

        editTrade(sale.rootId, TradeDraft(SELL, ram, today.minusDays(5), BROWN, PIECE, "1", "1200"))

        assertThat(Balance.of(balance(ram))).isEqualTo(Balance.ToReceive(rs(200)))
        assertConsistent(ram)
    }

    @Test
    fun `deleting a historical payment brings the balance back and keeps it visible`() = runTest {
        val ram = customer()
        trade(ram, SELL, "2000")
        val payment = pay(ram, PAYMENT_RECEIVED, "2500")
        assertThat(Balance.of(balance(ram))).isEqualTo(Balance.ToPay(rs(500)))

        delete(payment.rootId, "Cheque bounced")

        val view = ledgerOf(ram)
        assertThat(Balance.of(view.balance)).isEqualTo(Balance.ToReceive(rs(2_000)))
        assertThat(view.deleted.single().payment?.mode).isEqualTo(PaymentMode.CASH)
        assertThat(view.totals.received).isEqualTo(Money.ZERO)
        assertConsistent(ram)
    }

    @Test
    fun `changing the opening balance later recalculates everything after it`() = runTest {
        val ram = customer(opening = "1000")
        pay(ram, PAYMENT_RECEIVED, "400")
        updateCustomer(ram, CustomerDraft(name = "Ramesh", openingAmount = "1500", openingDate = today.minusDays(30)))

        val view = ledgerOf(ram)
        assertThat(view.lines.map { it.runningBalance }).containsExactly(rs(1_500), rs(1_100)).inOrder()
        assertThat(view.totals.opening).isEqualTo(rs(1_500))
        assertConsistent(ram)
    }

    @Test
    fun `receive changed to pay gets a PC- reference and moves the balance by twice the amount`() = runTest {
        val ram = customer()
        trade(ram, SELL, "1000")
        val receipt = pay(ram, PAYMENT_RECEIVED, "300")
        assertThat(balance(ram)).isEqualTo(rs(700))

        val draft = (paymentDraftOf(receipt.rootId) as DomainResult.Ok).value
        val changed = (editPayment(receipt.rootId, draft.copy(type = PAYMENT_MADE)) as DomainResult.Ok).value

        assertThat(changed.reference).isEqualTo("PC-000001")
        assertThat(balance(ram)).isEqualTo(rs(1_300))
        assertThat(audit.events.last().action).isEqualTo(AuditEvent.ACTION_TYPE_CHANGE)
    }

    @Test
    fun `moving a payment to another customer updates both balances together`() = runTest {
        val x = customer("Xavier")
        val y = customer("Yusuf")
        trade(x, SELL, "500")
        trade(y, SELL, "500")
        val payment = pay(x, PAYMENT_RECEIVED, "500")

        val draft = (paymentDraftOf(payment.rootId) as DomainResult.Ok).value
        editPayment(payment.rootId, draft.copy(customerId = y))

        assertThat(balance(x)).isEqualTo(rs(500))
        assertThat(balance(y)).isEqualTo(Money.ZERO)
    }

    @Test
    fun `editing preview leaves the edited payment out of the starting balance`() = runTest {
        val ram = customer()
        trade(ram, SELL, "2000")
        val payment = pay(ram, PAYMENT_RECEIVED, "500")
        val impact = preview(ram, PAYMENT_RECEIVED, rs(2_500), editingRootId = payment.rootId)
        assertThat(impact.outstanding).isEqualTo(rs(2_000))
        assertThat(impact.advance).isEqualTo(rs(500))
    }

    @Test
    fun `payment validation, double save and non-editable cases`() = runTest {
        val ram = customer()
        assertThat(recordPayment(PaymentDraft(PAYMENT_RECEIVED, ram, today, "0"), "x"))
            .isEqualTo(DomainResult.Err(DomainError.Validation(Field.AMOUNT, ValidationCode.MUST_BE_POSITIVE)))
        val first = recordPayment(PaymentDraft(PAYMENT_RECEIVED, ram, today, "10"), "same")
        assertThat(recordPayment(PaymentDraft(PAYMENT_RECEIVED, ram, today, "10"), "same")).isEqualTo(first)
        assertThat(ledgerOf(ram).lines).hasSize(1)

        val ref = (first as DomainResult.Ok).value
        delete(ref.rootId)
        val twenty = PaymentDraft(PAYMENT_RECEIVED, ram, today, "20")
        assertThat(editPayment(ref.rootId, twenty)).isEqualTo(DomainResult.Err(DomainError.EntryNotEditable))
        assertThat(paymentDraftOf(ref.rootId)).isEqualTo(DomainResult.Err(DomainError.EntryNotEditable))
        assertThat(editPayment(999, twenty)).isEqualTo(DomainResult.Err(DomainError.NotFound))
        val sale = trade(ram, SELL, "10")
        assertThat(editPayment(sale.rootId, twenty)).isEqualTo(DomainResult.Err(DomainError.EntryNotEditable))
        assertThat(editPayment(sale.rootId, twenty, reason = "x".repeat(201)))
            .isEqualTo(DomainResult.Err(DomainError.EntryNotEditable))
        assertThat(ledger.rows.value.count { it.status == EntryStatus.VOIDED }).isEqualTo(1)
    }

    /**
     * Property check over randomly generated histories (fixed seed, so failures reproduce): after any mix
     * of trades, payments, edits and deletes, the ledger, the customer list and the literal balance
     * formula always agree to the paisa.
     */
    @Test
    fun `random histories always satisfy the balance formula`() = runTest {
        val random = Random(seed = 20261108)
        repeat(40) { round ->
            val id = customer("Customer $round")
            val refs = mutableListOf<EntryRef>()
            repeat(random.nextInt(1, 15)) {
                val rupees = "${random.nextInt(1, 50_000)}.${random.nextInt(0, 100).toString().padStart(2, '0')}"
                val date = today.minusDays(random.nextLong(0, 60))
                refs += when (random.nextInt(4)) {
                    0 -> trade(id, SELL, rupees, date)
                    1 -> trade(id, BUY, rupees, date)
                    2 -> pay(id, PAYMENT_RECEIVED, rupees, date)
                    else -> pay(id, PAYMENT_MADE, rupees, date)
                }
            }
            if (random.nextBoolean()) delete(refs.random(random).rootId)
            val view = ledgerOf(id)
            val active = ledger.rows.value.filter { it.customerId == id && it.status == EntryStatus.ACTIVE }
            assertThat(view.balance).isEqualTo(LedgerCalculator.balance(active))
            assertThat(view.balance).isEqualTo(active.fold(Money.ZERO) { sum, e -> sum + e.balanceEffect })
            assertConsistent(id)
        }
    }
}
