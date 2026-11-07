package com.eggbusinessmanager.domain.usecase.customer

import com.eggbusinessmanager.domain.customer.AuditEvent
import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.customer.CustomerFilter
import com.eggbusinessmanager.domain.customer.CustomerQuery
import com.eggbusinessmanager.domain.customer.CustomerSort
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.ledger.Balance
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.OpeningDirection
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.repository.CustomerRepository
import com.eggbusinessmanager.testing.FakeAuditRepository
import com.eggbusinessmanager.testing.FakeCustomerRepository
import com.eggbusinessmanager.testing.FakeLedgerRepository
import com.eggbusinessmanager.testing.FixedClock
import com.eggbusinessmanager.testing.ImmediateTransactionRunner
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CustomerUseCasesTest {

    private val clock = FixedClock()
    private val today = clock.today()
    private val ledger = FakeLedgerRepository()
    private val customers = FakeCustomerRepository(ledger)
    private val audit = FakeAuditRepository()
    private val checks = CustomerChecks(customers, clock)
    private val tx = ImmediateTransactionRunner

    private val create = CreateCustomerUseCase(checks, customers, ledger, audit, tx, clock)
    private val update = UpdateCustomerUseCase(checks, customers, ledger, audit, tx, clock)
    private val activation = CustomerActivation(customers, audit, tx, clock)
    private val deactivate = DeactivateCustomerUseCase(activation)
    private val activate = ActivateCustomerUseCase(activation)
    private val delete = DeleteCustomerUseCase(customers, ledger, audit, tx, clock)
    private val canDelete = CanDeleteCustomerUseCase(ledger)
    private val search = SearchCustomersUseCase(customers)
    private val observe = ObserveCustomerUseCase(customers)
    private val history = ObserveCustomerLedgerUseCase(ledger)
    private val draftOf = GetCustomerDraftUseCase(customers, ledger)
    private val duplicateName = CheckDuplicateNameUseCase(customers)

    private suspend fun add(draft: CustomerDraft): Long = (create(draft) as DomainResult.Ok).value

    private fun owes(rupees: String, direction: OpeningDirection = OpeningDirection.CUSTOMER_OWES) =
        CustomerDraft(openingAmount = rupees, openingDirection = direction, openingDate = today)

    // ---- Create ----

    @Test
    fun `a customer with a name only is saved as Settled`() = runTest {
        val id = add(CustomerDraft(name = "Ram Traders"))

        val summary = checkNotNull(observe(id).first())
        assertThat(summary.customer.name).isEqualTo("Ram Traders")
        assertThat(summary.customer.isActive).isTrue()
        assertThat(summary.balance).isEqualTo(Balance.Settled)
        assertThat(ledger.rows.value).isEmpty()
        assertThat(audit.events.single().action).isEqualTo(AuditEvent.ACTION_CREATE)
    }

    @Test
    fun `an opening receivable becomes an OB entry and the balance shows To receive`() = runTest {
        val id = add(owes("1500").copy(name = "Ramesh"))

        assertThat(observe(id).first()?.balance).isEqualTo(Balance.ToReceive(Money.rupees(1_500)))
        val lines = history(id).first().lines
        assertThat(lines.single().entry.type).isEqualTo(EntryType.OPENING_BALANCE)
        assertThat(lines.single().entry.reference).isEqualTo("OB-000001")
        assertThat(lines.single().entry.date).isEqualTo(today)
    }

    @Test
    fun `an opening payable shows To pay`() = runTest {
        val id = add(owes("2,000.50", OpeningDirection.SHOP_OWES).copy(name = "Supplier Co"))
        assertThat(observe(id).first()?.balance).isEqualTo(Balance.ToPay(Money.rupees(2_000, 50)))
    }

    @Test
    fun `a zero opening amount creates no entry`() = runTest {
        val id = add(owes("0").copy(name = "Zero"))
        assertThat(ledger.hasEntries(id)).isFalse()
    }

    @Test
    fun `mobile numbers are unique across active and deactivated customers`() = runTest {
        val ram = add(CustomerDraft(name = "Ram", mobile = "9876543210"))
        deactivate(ram)

        val result = create(CustomerDraft(name = "Shyam", mobile = "+91 98765 43210"))

        assertThat(result).isEqualTo(DomainResult.Err(DomainError.DuplicateMobile(ram, "Ram")))
    }

    @Test
    fun `invalid input is rejected and nothing is stored`() = runTest {
        val result = create(CustomerDraft(name = "", openingAmount = "abc"))
        val fields = ((result as DomainResult.Err).error as DomainError.Validation).fields
        assertThat(fields).containsExactly(
            Field.CUSTOMER_NAME, ValidationCode.REQUIRED,
            Field.OPENING_AMOUNT, ValidationCode.INVALID_FORMAT,
        )
        assertThat(customers.customers.value).isEmpty()
    }

    @Test
    fun `duplicate names are allowed but detected for a warning`() = runTest {
        val first = add(CustomerDraft(name = "Ram Traders"))
        assertThat(duplicateName(" ram  traders")).isTrue()
        assertThat(duplicateName("Ram Traders", excludingId = first)).isFalse()
        assertThat(create(CustomerDraft(name = "Ram Traders"))).isInstanceOf(DomainResult.Ok::class.java)
        assertThat(duplicateName("")).isFalse()
    }

    @Test
    fun `a failing duplicate-name lookup never blocks the owner`() = runTest {
        val broken = object : CustomerRepository by customers {
            override suspend fun nameExists(name: String, excludingId: Long?): Boolean = error("db")
        }
        assertThat(CheckDuplicateNameUseCase(broken)("Ram")).isFalse()
    }

    // ---- Update ----

    @Test
    fun `editing contact details keeps the balance and is audited`() = runTest {
        val id = add(owes("500").copy(name = "Ram"))

        val result = update(id, draftOf(id).let { (it as DomainResult.Ok).value }.copy(address = "Market Yard", notes = "Pays weekly"))

        assertThat(result).isEqualTo(DomainResult.Ok(Unit))
        val customer = checkNotNull(customers.get(id))
        assertThat(customer.address).isEqualTo("Market Yard")
        assertThat(customer.notes).isEqualTo("Pays weekly")
        assertThat(observe(id).first()?.netBalance).isEqualTo(Money.rupees(500))
        assertThat(audit.events.map { it.action }).containsExactly(AuditEvent.ACTION_CREATE, AuditEvent.ACTION_EDIT).inOrder()
    }

    @Test
    fun `changing the opening balance supersedes it with a revision and audits old and new values`() = runTest {
        val id = add(owes("500").copy(name = "Ram"))
        val before = checkNotNull(ledger.activeOpening(id))

        update(id, owes("750", OpeningDirection.SHOP_OWES).copy(name = "Ram"))

        val after = checkNotNull(ledger.activeOpening(id))
        assertThat(after.rootId).isEqualTo(before.rootId)
        assertThat(after.reference).isEqualTo(before.reference)
        assertThat(ledger.rows.value.first { it.id == before.id }.status).isEqualTo(EntryStatus.SUPERSEDED)
        assertThat(observe(id).first()?.balance).isEqualTo(Balance.ToPay(Money.rupees(750)))
        val change = audit.events.single { it.action == AuditEvent.ACTION_OPENING_CHANGE }
        assertThat(change.before?.get("amount_paise")).isEqualTo("50000")
        assertThat(change.after?.get("amount_paise")).isEqualTo("75000")
        assertThat(change.after?.get("direction")).isEqualTo("SHOP_OWES")
    }

    @Test
    fun `clearing the opening amount revises it to zero instead of deleting history`() = runTest {
        val id = add(owes("500").copy(name = "Ram"))

        update(id, CustomerDraft(name = "Ram"))

        assertThat(observe(id).first()?.balance).isEqualTo(Balance.Settled)
        assertThat(ledger.rows.value).hasSize(2)
        // Editing again without an amount records nothing new.
        update(id, CustomerDraft(name = "Ram"))
        assertThat(ledger.rows.value).hasSize(2)
    }

    @Test
    fun `adding an opening balance later creates the entry`() = runTest {
        val id = add(CustomerDraft(name = "Ram"))
        update(id, owes("300").copy(name = "Ram"))
        assertThat(observe(id).first()?.netBalance).isEqualTo(Money.rupees(300))
    }

    @Test
    fun `an unchanged opening balance is not revised`() = runTest {
        val id = add(owes("300").copy(name = "Ram"))
        update(id, owes("300.00").copy(name = "Ram Traders"))
        assertThat(ledger.rows.value).hasSize(1)
    }

    @Test
    fun `a customer may keep their own mobile number when edited`() = runTest {
        val id = add(CustomerDraft(name = "Ram", mobile = "9876543210"))
        assertThat(update(id, CustomerDraft(name = "Ram T", mobile = "9876543210"))).isEqualTo(DomainResult.Ok(Unit))
    }

    @Test
    fun `editing or loading a missing customer reports NotFound`() = runTest {
        assertThat(update(42, CustomerDraft(name = "X Y"))).isEqualTo(DomainResult.Err(DomainError.NotFound))
        assertThat(draftOf(42)).isEqualTo(DomainResult.Err(DomainError.NotFound))
        assertThat(deactivate(42)).isEqualTo(DomainResult.Err(DomainError.NotFound))
        assertThat(delete(42)).isEqualTo(DomainResult.Err(DomainError.NotFound))
    }

    @Test
    fun `the editor draft shows the current opening balance`() = runTest {
        val id = add(owes("1500.5", OpeningDirection.SHOP_OWES).copy(name = "Ram", mobile = "9876543210"))
        val draft = (draftOf(id) as DomainResult.Ok).value
        assertThat(draft.openingAmount).isEqualTo("1500.50")
        assertThat(draft.openingDirection).isEqualTo(OpeningDirection.SHOP_OWES)
        assertThat(draft.mobile).isEqualTo("9876543210")
    }

    // ---- Deactivate / activate / delete ----

    @Test
    fun `deactivated customers leave the default list but keep their balance`() = runTest {
        val id = add(owes("100").copy(name = "Ram"))

        deactivate(id)

        assertThat(search(CustomerQuery()).first()).isEmpty()
        val deactivated = search(CustomerQuery(filter = CustomerFilter.DEACTIVATED)).first()
        assertThat(deactivated.single().netBalance).isEqualTo(Money.rupees(100))

        activate(id)
        assertThat(search(CustomerQuery()).first().single().customer.isActive).isTrue()
        assertThat(audit.events.map { it.action }).containsAtLeast(AuditEvent.ACTION_ARCHIVE, AuditEvent.ACTION_UNARCHIVE).inOrder()
    }

    @Test
    fun `deactivating twice is harmless and audited once`() = runTest {
        val id = add(CustomerDraft(name = "Ram"))
        deactivate(id)
        deactivate(id)
        assertThat(audit.events.count { it.action == AuditEvent.ACTION_ARCHIVE }).isEqualTo(1)
    }

    @Test
    fun `a customer with financial entries cannot be deleted`() = runTest {
        val id = add(owes("100").copy(name = "Ram"))

        assertThat(canDelete(id)).isFalse()
        assertThat(delete(id)).isEqualTo(DomainResult.Err(DomainError.CustomerHasEntries))
        assertThat(customers.get(id)).isNotNull()
    }

    @Test
    fun `even a zeroed opening history blocks deletion`() = runTest {
        val id = add(owes("100").copy(name = "Ram"))
        update(id, CustomerDraft(name = "Ram"))
        assertThat(delete(id)).isEqualTo(DomainResult.Err(DomainError.CustomerHasEntries))
    }

    @Test
    fun `a customer with no entries can be deleted permanently`() = runTest {
        val id = add(CustomerDraft(name = "Typo Customer"))

        assertThat(canDelete(id)).isTrue()
        assertThat(delete(id)).isEqualTo(DomainResult.Ok(Unit))
        assertThat(customers.get(id)).isNull()
        val deletion = audit.events.last()
        assertThat(deletion.action).isEqualTo(AuditEvent.ACTION_DELETE)
        assertThat(deletion.customerId).isNull()
        assertThat(deletion.before?.get("name")).isEqualTo("Typo Customer")
    }

    @Test
    fun `the database backstop also refuses deletion`() = runTest {
        val id = add(CustomerDraft(name = "Ram"))
        val raced = DeleteCustomerUseCase(
            object : CustomerRepository by customers {
                override suspend fun deleteIfUnused(id: Long) = false
            },
            ledger,
            audit,
            tx,
            clock,
        )
        assertThat(raced(id)).isEqualTo(DomainResult.Err(DomainError.CustomerHasEntries))
        assertThat(audit.events.none { it.action == AuditEvent.ACTION_DELETE }).isTrue()
    }

    // ---- Search, filter, sort ----

    @Test
    fun `search matches any part of the name or mobile`() = runTest {
        add(CustomerDraft(name = "Ramesh Patil", mobile = "9876500001"))
        add(CustomerDraft(name = "Shriram Eggs", mobile = "9000012345"))
        add(CustomerDraft(name = "Gopal", mobile = "9123498765"))

        assertThat(search(CustomerQuery(text = "RAM")).first().map { it.customer.name })
            .containsExactly("Ramesh Patil", "Shriram Eggs")
        assertThat(search(CustomerQuery(text = "9876")).first().map { it.customer.name })
            .containsExactly("Ramesh Patil", "Gopal")
        assertThat(search(CustomerQuery(text = "zzz")).first()).isEmpty()
    }

    @Test
    fun `filters and sorts by balance`() = runTest {
        add(owes("100").copy(name = "Small Receivable"))
        add(owes("900").copy(name = "Big Receivable"))
        add(owes("500", OpeningDirection.SHOP_OWES).copy(name = "Payable"))
        add(CustomerDraft(name = "Settled"))

        assertThat(names(CustomerQuery(filter = CustomerFilter.TO_RECEIVE, sort = CustomerSort.HIGHEST_RECEIVABLE)))
            .containsExactly("Big Receivable", "Small Receivable").inOrder()
        assertThat(names(CustomerQuery(filter = CustomerFilter.TO_PAY))).containsExactly("Payable")
        assertThat(names(CustomerQuery(filter = CustomerFilter.SETTLED))).containsExactly("Settled")
        assertThat(names(CustomerQuery(sort = CustomerSort.HIGHEST_PAYABLE)).first()).isEqualTo("Payable")
        assertThat(names(CustomerQuery(sort = CustomerSort.NAME)))
            .containsExactly("Big Receivable", "Payable", "Settled", "Small Receivable").inOrder()
    }

    @Test
    fun `trades in both directions net into one balance for the same customer`() = runTest {
        val id = add(CustomerDraft(name = "Both Ways"))
        ledger.addEntry(id, EntryType.SELL, Money.rupees(5_000), today, clock.now())
        ledger.addEntry(id, EntryType.BUY, Money.rupees(3_000), today, clock.now())

        assertThat(observe(id).first()?.balance).isEqualTo(Balance.ToReceive(Money.rupees(2_000)))
        assertThat(history(id).first().lines.map { it.runningBalance })
            .containsExactly(Money.rupees(5_000), Money.rupees(2_000)).inOrder()
    }

    private suspend fun names(query: CustomerQuery) = search(query).first().map { it.customer.name }
}
