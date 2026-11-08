package com.eggbusinessmanager.domain.usecase.trade

import com.eggbusinessmanager.domain.customer.AuditEvent
import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.ledger.Balance
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerCalculator
import com.eggbusinessmanager.domain.ledger.OpeningDirection
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.trade.TradeDraft
import com.eggbusinessmanager.domain.usecase.catalog.SaveUnitUseCase
import com.eggbusinessmanager.domain.usecase.customer.CreateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.CustomerActivation
import com.eggbusinessmanager.domain.usecase.customer.CustomerChecks
import com.eggbusinessmanager.domain.usecase.customer.ObserveCustomerLedgerUseCase
import com.eggbusinessmanager.domain.usecase.entry.EntryWriter
import com.eggbusinessmanager.testing.FakeAuditRepository
import com.eggbusinessmanager.testing.FakeCatalogRepository
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.BOX
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.BROWN
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.DOZEN
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.TRAY
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.WHITE
import com.eggbusinessmanager.testing.FakeCustomerRepository
import com.eggbusinessmanager.testing.FakeLedgerRepository
import com.eggbusinessmanager.testing.FixedClock
import com.eggbusinessmanager.testing.ImmediateTransactionRunner
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** US3 (record), US5-4/5/8/9 (edit, delete, customer and type change), FR-024 (last price). */
class TradeUseCasesTest {

    private val clock = FixedClock()
    private val today = clock.today()
    private val ledger = FakeLedgerRepository()
    private val customers = FakeCustomerRepository(ledger)
    private val catalog = FakeCatalogRepository()
    private val audit = FakeAuditRepository()
    private val tx = ImmediateTransactionRunner
    private val checks = TradeChecks(customers, catalog, clock)

    private val writer = EntryWriter(ledger, audit, tx, clock)
    private val record = RecordTradeUseCase(checks, writer)
    private val edit = EditTradeUseCase(checks, ledger, writer)
    private val delete = DeleteEntryUseCase(ledger, audit, tx, clock)
    private val observe = ObserveEntryUseCase(ledger)
    private val draftOf = GetTradeDraftUseCase(ledger)
    private val suggest = SuggestPriceUseCase(ledger)
    private val history = ObserveCustomerLedgerUseCase(ledger)
    private val createCustomer = CreateCustomerUseCase(CustomerChecks(customers, clock), customers, ledger, audit, tx, clock)
    private val saveUnit = SaveUnitUseCase(catalog, audit, tx, clock)

    private suspend fun customer(name: String, opening: String = ""): Long =
        (createCustomer(CustomerDraft(name = name, openingAmount = opening, openingDate = today)) as DomainResult.Ok).value

    private fun sale(customerId: Long, qty: String = "10", price: String = "180", unit: Long = TRAY, variety: Long = BROWN) =
        TradeDraft(EntryType.SELL, customerId, today, variety, unit, qty, price)

    private suspend fun balance(customerId: Long) = history(customerId).first().balance

    private suspend fun recorded(draft: TradeDraft, request: String = draft.hashCode().toString() + Math.random()): EntryRef =
        (record(draft, request) as DomainResult.Ok).value

    // ---- Record (US3) ----

    @Test
    fun `US3-2 a sale raises the balance by its total and gets an S- reference`() = runTest {
        val ram = customer("Ram")

        val ref = recorded(sale(ram))

        assertThat(ref.reference).isEqualTo("S-000001")
        assertThat(balance(ram)).isEqualTo(Money.rupees(1_800))
        val line = history(ram).first().lines.single()
        assertThat(line.entry.trade?.pieces).isEqualTo(300L)
        assertThat(line.runningBalance).isEqualTo(Money.rupees(1_800))
        assertThat(audit.events.last().action).isEqualTo(AuditEvent.ACTION_CREATE)
    }

    @Test
    fun `US3-3 a purchase lowers the balance and gets a B- reference`() = runTest {
        val farm = customer("Farm")
        val ref = recorded(sale(farm, qty = "2", price = "1000", unit = BOX).copy(type = EntryType.BUY))
        assertThat(ref.reference).isEqualTo("B-000001")
        assertThat(Balance.of(balance(farm))).isEqualTo(Balance.ToPay(Money.rupees(2_000)))
    }

    @Test
    fun `references are per type and never reused`() = runTest {
        val ram = customer("Ram")
        assertThat(recorded(sale(ram)).reference).isEqualTo("S-000001")
        assertThat(recorded(sale(ram).copy(type = EntryType.BUY)).reference).isEqualTo("B-000001")
        val second = recorded(sale(ram))
        assertThat(second.reference).isEqualTo("S-000002")
        delete(second.rootId)
        assertThat(recorded(sale(ram)).reference).isEqualTo("S-000003")
    }

    @Test
    fun `US3-8 saving twice with the same request id creates one entry`() = runTest {
        val ram = customer("Ram")
        val first = record(sale(ram), "request-1")
        val second = record(sale(ram), "request-1")

        assertThat(second).isEqualTo(first)
        assertThat(ledger.rows.value.count { it.type == EntryType.SELL }).isEqualTo(1)
        assertThat(balance(ram)).isEqualTo(Money.rupees(1_800))
    }

    @Test
    fun `invalid trades are not saved`() = runTest {
        val ram = customer("Ram")
        val result = record(sale(ram, qty = "0.05"), "r")
        assertThat(result).isEqualTo(DomainResult.Err(DomainError.Validation(Field.QUANTITY, ValidationCode.NOT_WHOLE_PIECES)))
        assertThat(ledger.rows.value).isEmpty()
    }

    @Test
    fun `deactivated customers cannot get new trades`() = runTest {
        val ram = customer("Ram")
        CustomerActivation(customers, audit, tx, clock).set(ram, active = false)
        val result = record(sale(ram), "r")
        assertThat(result).isEqualTo(DomainResult.Err(DomainError.Validation(Field.CUSTOMER, ValidationCode.INACTIVE)))
    }

    @Test
    fun `units keep their own conversion factors`() = runTest {
        val ram = customer("Ram")
        val trays = recorded(sale(ram, qty = "1", unit = TRAY))
        val dozens = recorded(sale(ram, qty = "1", unit = DOZEN))
        val boxes = recorded(sale(ram, qty = "1", unit = BOX))
        val pieces = listOf(trays, dozens, boxes).map { ledger.currentRevision(it.rootId)?.trade?.pieces }
        assertThat(pieces).containsExactly(30L, 12L, 210L).inOrder()
    }

    @Test
    fun `US12-3 resizing a unit never changes past entries, even when they are edited`() = runTest {
        val ram = customer("Ram")
        val box = recorded(sale(ram, qty = "2", price = "1000", unit = BOX))

        saveUnit(BOX, "Box", "180")

        assertThat(ledger.currentRevision(box.rootId)?.trade?.pieces).isEqualTo(420L)
        // Editing only the remarks keeps the 210-piece box it was saved with.
        edit(box.rootId, sale(ram, qty = "2", price = "1000", unit = BOX).copy(remarks = "checked"))
        assertThat(ledger.currentRevision(box.rootId)?.trade?.piecesPerUnit).isEqualTo(210)
        // A new sale uses the new size.
        val next = recorded(sale(ram, qty = "2", price = "1000", unit = BOX))
        assertThat(ledger.currentRevision(next.rootId)?.trade?.pieces).isEqualTo(360L)
    }

    // ---- Price suggestion (FR-024) ----

    @Test
    fun `last price is per customer, type, variety and unit`() = runTest {
        val ram = customer("Ram")
        val shyam = customer("Shyam")
        recorded(sale(ram, price = "175"))
        recorded(sale(ram, price = "182.50"))
        recorded(sale(shyam, price = "150"))

        assertThat(suggest(ram, EntryType.SELL, BROWN, TRAY)).isEqualTo(Money.rupees(182, 50))
        assertThat(suggest(shyam, EntryType.SELL, BROWN, TRAY)).isEqualTo(Money.rupees(150))
        assertThat(suggest(ram, EntryType.BUY, BROWN, TRAY)).isNull()
        assertThat(suggest(ram, EntryType.SELL, WHITE, TRAY)).isNull()
        assertThat(suggest(ram, EntryType.SELL, BROWN, DOZEN)).isNull()
    }

    // ---- Edit (FR-033) ----

    @Test
    fun `editing keeps the reference, supersedes the original and updates the balance`() = runTest {
        val ram = customer("Ram")
        val ref = recorded(sale(ram))

        val result = edit(ref.rootId, sale(ram, qty = "12"), reason = "Counted again")

        assertThat(result).isEqualTo(DomainResult.Ok(ref))
        assertThat(balance(ram)).isEqualTo(Money.rupees(2_160))
        val detail = checkNotNull(observe(ref.rootId).first())
        assertThat(detail.revisions.map { it.status }).containsExactly(EntryStatus.SUPERSEDED, EntryStatus.ACTIVE).inOrder()
        assertThat(detail.current.reference).isEqualTo("S-000001")
        assertThat(detail.current.supersedesId).isEqualTo(detail.revisions.first().id)
        val change = audit.events.last()
        assertThat(change.action).isEqualTo(AuditEvent.ACTION_EDIT)
        assertThat(change.reason).isEqualTo("Counted again")
        assertThat(change.before?.get("amount_paise")).isEqualTo("180000")
        assertThat(change.after?.get("amount_paise")).isEqualTo("216000")
    }

    @Test
    fun `an edit with no changes stores nothing`() = runTest {
        val ram = customer("Ram")
        val ref = recorded(sale(ram))
        edit(ref.rootId, sale(ram))
        assertThat(ledger.rows.value).hasSize(1)
    }

    @Test
    fun `US5-8 changing the customer moves the amount between both balances together`() = runTest {
        val x = customer("Xavier")
        val y = customer("Yusuf")
        val ref = recorded(sale(x, qty = "1", price = "1000", unit = 1))

        edit(ref.rootId, sale(y, qty = "1", price = "1000", unit = 1))

        assertThat(balance(x)).isEqualTo(Money.ZERO)
        assertThat(balance(y)).isEqualTo(Money.rupees(1_000))
        assertThat(audit.events.filter { it.action == AuditEvent.ACTION_CUSTOMER_CHANGE }.map { it.customerId }).containsExactly(x, y)
    }

    @Test
    fun `US5-9 SELL changed to BUY gets a new B- reference and moves the balance by twice the total`() = runTest {
        val ram = customer("Ram")
        val sold = recorded(sale(ram))
        assertThat(balance(ram)).isEqualTo(Money.rupees(1_800))

        val bought = (edit(sold.rootId, sale(ram).copy(type = EntryType.BUY)) as DomainResult.Ok).value

        assertThat(bought.reference).isEqualTo("B-000001")
        assertThat(bought.rootId).isNotEqualTo(sold.rootId)
        assertThat(balance(ram)).isEqualTo(Money.rupees(-1_800))
        val old = checkNotNull(observe(sold.rootId).first())
        assertThat(old.current.status).isEqualTo(EntryStatus.SUPERSEDED)
        assertThat(old.replacedBy).isEqualTo("B-000001")
        assertThat(audit.events.last().action).isEqualTo(AuditEvent.ACTION_TYPE_CHANGE)
    }

    @Test
    fun `edit impact shows the change for every affected customer`() = runTest {
        val x = customer("Xavier")
        val ref = recorded(sale(x))
        val current = checkNotNull(ledger.currentRevision(ref.rootId))

        assertThat(EditImpact.of(current, x, EntryType.SELL, Money.rupees(2_000))).containsExactly(x, Money.rupees(200))
        assertThat(EditImpact.of(current, 99, EntryType.SELL, Money.rupees(1_800)))
            .containsExactly(x, Money.rupees(-1_800), 99L, Money.rupees(1_800))
        assertThat(EditImpact.of(current, x, EntryType.BUY, Money.rupees(1_800))).containsExactly(x, Money.rupees(-3_600))
        assertThat(EditImpact.of(current, x, EntryType.SELL, Money.rupees(1_800))).isEmpty()
    }

    @Test
    fun `the editor draft reproduces the saved values`() = runTest {
        val ram = customer("Ram")
        val ref = recorded(sale(ram, qty = "7.5", price = "183.50").copy(remarks = "morning"))
        val draft = (draftOf(ref.rootId) as DomainResult.Ok).value
        assertThat(draft).isEqualTo(sale(ram, qty = "7.5", price = "183.50").copy(remarks = "morning"))
    }

    @Test
    fun `edit reason is limited to 200 characters`() = runTest {
        val ram = customer("Ram")
        val ref = recorded(sale(ram))
        assertThat(edit(ref.rootId, sale(ram, qty = "11"), reason = "x".repeat(201)))
            .isEqualTo(DomainResult.Err(DomainError.Validation(Field.REASON, ValidationCode.TOO_LONG)))
    }

    // ---- Delete = void (FR-034/035) ----

    @Test
    fun `US5-5 a deleted entry stays visible but counts nowhere and cannot be edited`() = runTest {
        val ram = customer("Ram", opening = "500")
        val ref = recorded(sale(ram))
        assertThat(balance(ram)).isEqualTo(Money.rupees(2_300))

        assertThat(delete(ref.rootId, "Wrong customer")).isEqualTo(DomainResult.Ok(Unit))

        assertThat(balance(ram)).isEqualTo(Money.rupees(500))
        val ledgerView = history(ram).first()
        assertThat(ledgerView.deleted.single().reference).isEqualTo("S-000001")
        assertThat(ledgerView.deleted.single().voidReason).isEqualTo("Wrong customer")
        assertThat(ledgerView.lines.map { it.entry.type }).containsExactly(EntryType.OPENING_BALANCE)
        assertThat(edit(ref.rootId, sale(ram, qty = "1"))).isEqualTo(DomainResult.Err(DomainError.EntryNotEditable))
        assertThat(delete(ref.rootId)).isEqualTo(DomainResult.Err(DomainError.EntryNotEditable))
        assertThat(draftOf(ref.rootId)).isEqualTo(DomainResult.Err(DomainError.EntryNotEditable))
        assertThat(ledger.rows.value).hasSize(2) // nothing was removed
        assertThat(audit.events.last().action).isEqualTo(AuditEvent.ACTION_VOID)
    }

    @Test
    fun `missing entries are reported`() = runTest {
        assertThat(delete(404)).isEqualTo(DomainResult.Err(DomainError.NotFound))
        assertThat(edit(404, sale(1))).isEqualTo(DomainResult.Err(DomainError.NotFound))
        assertThat(draftOf(404)).isEqualTo(DomainResult.Err(DomainError.NotFound))
        assertThat(observe(404).first()).isNull()
    }

    @Test
    fun `ledger balance always equals the sum of active entries`() = runTest {
        val ram = customer("Ram", opening = "250.75")
        val a = recorded(sale(ram, qty = "3", price = "181.25"))
        recorded(sale(ram, qty = "1", price = "999.99", unit = BOX).copy(type = EntryType.BUY))
        edit(a.rootId, sale(ram, qty = "4", price = "181.25"))
        val c = recorded(sale(ram, qty = "2.5", price = "12.10", unit = DOZEN))
        delete(c.rootId)

        val rows = ledger.rows.value.filter { it.customerId == ram }
        assertThat(balance(ram)).isEqualTo(LedgerCalculator.balance(rows))
        // 250.75 + 4×181.25 − 999.99 = −24.24
        assertThat(balance(ram)).isEqualTo(Money(-2_424))
    }

    @Test
    fun `opening balances cannot be edited as trades`() = runTest {
        val ram = customer("Ram", opening = "100")
        val opening = checkNotNull(ledger.activeOpening(ram))
        assertThat(edit(opening.rootId, sale(ram))).isEqualTo(DomainResult.Err(DomainError.EntryNotEditable))
        assertThat(OpeningDirection.CUSTOMER_OWES).isEqualTo(opening.openingDirection)
    }
}
