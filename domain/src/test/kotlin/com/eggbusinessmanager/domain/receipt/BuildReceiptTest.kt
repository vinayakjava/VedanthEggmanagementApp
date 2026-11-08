package com.eggbusinessmanager.domain.receipt

import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.PaymentMode
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.payment.PaymentDraft
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.trade.PaidNow
import com.eggbusinessmanager.domain.trade.TradeDraft
import com.eggbusinessmanager.domain.usecase.auth.AuthHarness
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
import com.eggbusinessmanager.testing.FakeBusinessProfileRepository
import com.eggbusinessmanager.testing.FakeCatalogRepository
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.BROWN
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.TRAY
import com.eggbusinessmanager.testing.FakeCustomerRepository
import com.eggbusinessmanager.testing.FakeLedgerRepository
import com.eggbusinessmanager.testing.ImmediateTransactionRunner
import com.eggbusinessmanager.testing.SignupForms
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** FR-042a: what a receipt contains, and which entries have none. */
class BuildReceiptTest {

    private val auth = AuthHarness()
    private val clock = auth.clock
    private val today = clock.today()
    private val ledger = FakeLedgerRepository()
    private val customers = FakeCustomerRepository(ledger)
    private val audit = FakeAuditRepository()
    private val tx = ImmediateTransactionRunner
    private val writer = EntryWriter(ledger, audit, tx, clock)
    private val tradeChecks = TradeChecks(customers, FakeCatalogRepository(), clock)
    private val record = RecordTradeUseCase(tradeChecks, writer)
    private val edit = EditTradeUseCase(tradeChecks, ledger, writer)
    private val pay = RecordPaymentUseCase(PaymentChecks(customers, clock), writer)
    private val delete = DeleteEntryUseCase(ledger, audit, tx, clock)
    private val createCustomer = CreateCustomerUseCase(CustomerChecks(customers, clock), customers, ledger, audit, tx, clock)

    private suspend fun receipts(): BuildReceiptUseCase {
        auth.signUp(SignupForms.valid)
        return BuildReceiptUseCase(ledger, customers, FakeBusinessProfileRepository(auth.accounts.profile), auth.accounts)
    }

    private suspend fun customer(opening: String = ""): Long =
        (createCustomer(CustomerDraft(name = "Ramesh Traders", mobile = "9876543210", openingAmount = opening, openingDate = today))
            as DomainResult.Ok).value

    /** 10 trays at ₹180 = ₹1,800. */
    private fun sale(customerId: Long, paid: String = "") =
        TradeDraft(EntryType.SELL, customerId, today, BROWN, TRAY, "10", "180", paidNow = PaidNow(paid, PaymentMode.UPI))

    private suspend fun recorded(draft: TradeDraft): EntryRef = (record(draft, "r" + Math.random()) as DomainResult.Ok).value

    private suspend fun BuildReceiptUseCase.ok(rootId: Long) = (this(rootId) as DomainResult.Ok).value

    @Test
    fun `a sale receipt has the shop, customer, linked payment and both balances`() = runTest {
        val build = receipts()
        val ram = customer(opening = "1000")
        val sale = recorded(sale(ram, paid = "500"))
        // A later payment changes the current balance but not the balance after the sale.
        pay(PaymentDraft(EntryType.PAYMENT_RECEIVED, ram, today, "300"), "later")

        val receipt = build.ok(sale.rootId)

        assertThat(receipt.entry.reference).isEqualTo("S-000001")
        assertThat(receipt.revised).isFalse()
        assertThat(receipt.customer.mobile).isEqualTo("9876543210")
        assertThat(receipt.shop?.shopName).isEqualTo(SignupForms.valid.shopName)
        assertThat(receipt.shopPhone).isEqualTo(SignupForms.MOBILE)
        assertThat(receipt.linked?.reference).isEqualTo("RP-000001")
        assertThat(receipt.balanceAfter).isEqualTo(Money.rupees(2_800)) // ₹1,000 opening + ₹1,800 sale
        assertThat(receipt.currentBalance).isEqualTo(Money.rupees(2_000)) // − ₹500 now − ₹300 later
    }

    @Test
    fun `a corrected entry is labelled revised and shows the corrected values`() = runTest {
        val build = receipts()
        val ram = customer()
        val sale = recorded(sale(ram))
        edit(sale.rootId, sale(ram).copy(quantity = "12"))

        val receipt = build.ok(sale.rootId)

        assertThat(receipt.revised).isTrue()
        assertThat(receipt.entry.amount).isEqualTo(Money.rupees(2_160))
    }

    @Test
    fun `deleted entries, opening balances and unknown ids have no receipt`() = runTest {
        val build = receipts()
        val ram = customer(opening = "1000")
        val sale = recorded(sale(ram))
        delete(sale.rootId)
        assertThat(build(sale.rootId)).isEqualTo(DomainResult.Err(DomainError.EntryNotEditable))

        val opening = ledger.activeOpening(ram)!!
        assertThat(build(opening.rootId)).isEqualTo(DomainResult.Err(DomainError.EntryNotEditable))
        assertThat(build(999)).isEqualTo(DomainResult.Err(DomainError.NotFound))
    }

    @Test
    fun `a deleted linked payment is left off the sale's receipt`() = runTest {
        val build = receipts()
        val ram = customer()
        val sale = recorded(sale(ram, paid = "500"))
        delete(build.ok(sale.rootId).linked!!.rootId)
        assertThat(build.ok(sale.rootId).linked).isNull()
    }
}
