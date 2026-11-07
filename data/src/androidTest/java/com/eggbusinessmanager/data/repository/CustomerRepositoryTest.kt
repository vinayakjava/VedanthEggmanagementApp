package com.eggbusinessmanager.data.repository

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.data.db.DatabaseFactory
import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.data.db.RoomTransactionRunner
import com.eggbusinessmanager.data.db.entity.LedgerEntryEntity
import com.eggbusinessmanager.domain.customer.AuditEvent
import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.customer.CustomerFilter
import com.eggbusinessmanager.domain.customer.CustomerQuery
import com.eggbusinessmanager.domain.customer.CustomerSort
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.ledger.Balance
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerCalculator
import com.eggbusinessmanager.domain.ledger.OpeningDirection
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.usecase.customer.CreateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.CustomerActivation
import com.eggbusinessmanager.domain.usecase.customer.CustomerChecks
import com.eggbusinessmanager.domain.usecase.customer.DeleteCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.UpdateCustomerUseCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

/** Customers, opening balances and their integrity rules on the real encrypted database (US2). */
@RunWith(AndroidJUnit4::class)
class CustomerRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileName = "customer-repo-test.db"
    private lateinit var db: EggBusinessDatabase
    private lateinit var customers: RoomCustomerRepository
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var create: CreateCustomerUseCase
    private lateinit var update: UpdateCustomerUseCase
    private lateinit var delete: DeleteCustomerUseCase
    private lateinit var activation: CustomerActivation

    private val today = LocalDate.of(2026, 11, 8)
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-11-08T10:00:00Z")
        override fun today(): LocalDate = today
    }

    @Before
    fun setUp() {
        context.deleteDatabase(fileName)
        db = DatabaseFactory.build(context, ByteArray(32).also { SecureRandom().nextBytes(it) }, fileName)
        val provider = Provider { db }
        customers = RoomCustomerRepository(provider, Dispatchers.IO)
        ledger = RoomLedgerRepository(provider, Dispatchers.IO)
        val audit = RoomAuditRepository(provider, Dispatchers.IO)
        val tx = RoomTransactionRunner(provider)
        val checks = CustomerChecks(customers, clock)
        create = CreateCustomerUseCase(checks, customers, ledger, audit, tx, clock)
        update = UpdateCustomerUseCase(checks, customers, ledger, audit, tx, clock)
        delete = DeleteCustomerUseCase(customers, ledger, audit, tx, clock)
        activation = CustomerActivation(customers, audit, tx, clock)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(fileName)
    }

    private suspend fun add(
        name: String,
        mobile: String = "",
        opening: String = "",
        direction: OpeningDirection = OpeningDirection.CUSTOMER_OWES,
    ): Long {
        val draft = CustomerDraft(name = name, mobile = mobile, openingAmount = opening, openingDirection = direction, openingDate = today)
        return (create(draft) as DomainResult.Ok).value
    }

    private suspend fun names(query: CustomerQuery) = customers.search(query).first().map { it.customer.name }

    @Test
    fun openingBalanceIsALedgerEntryWithAnObReference() = runTest {
        val id = add("Ramesh", opening = "1,500")

        val summary = checkNotNull(customers.observe(id).first())
        assertThat(summary.balance).isEqualTo(Balance.ToReceive(Money.rupees(1_500)))
        assertThat(summary.lastActivity).isEqualTo(today)
        val entry = ledger.observeEntries(id).first().single()
        assertThat(entry.type).isEqualTo(EntryType.OPENING_BALANCE)
        assertThat(entry.reference).isEqualTo("OB-000001")
        assertThat(entry.rootId).isEqualTo(entry.id)
        assertThat(add("Second", opening = "10").let { ledger.activeOpening(it)?.reference }).isEqualTo("OB-000002")
    }

    @Test
    fun searchMatchesNameAnywhereCaseInsensitiveAndMobileDigits() = runTest {
        add("Ramesh Patil", mobile = "9876500001")
        add("Shriram Eggs", mobile = "9000012345")
        add("Gopal", mobile = "9123498765")
        add("100% Pure_Eggs")

        assertThat(names(CustomerQuery(text = "RAM"))).containsExactly("Ramesh Patil", "Shriram Eggs")
        assertThat(names(CustomerQuery(text = "9876"))).containsExactly("Ramesh Patil", "Gopal")
        // LIKE wildcards in the query are matched literally.
        assertThat(names(CustomerQuery(text = "%"))).containsExactly("100% Pure_Eggs")
        assertThat(names(CustomerQuery(text = "e_g"))).isEmpty()
    }

    @Test
    fun filtersAndSortsUseSqlBalances() = runTest {
        add("Small Receivable", opening = "100")
        add("Big Receivable", opening = "900")
        add("Payable", opening = "500", direction = OpeningDirection.SHOP_OWES)
        add("Settled")
        val gone = add("Gone")
        activation.set(gone, active = false)

        assertThat(names(CustomerQuery())).containsExactly("Big Receivable", "Payable", "Settled", "Small Receivable").inOrder()
        assertThat(names(CustomerQuery(filter = CustomerFilter.TO_RECEIVE, sort = CustomerSort.HIGHEST_RECEIVABLE)))
            .containsExactly("Big Receivable", "Small Receivable").inOrder()
        assertThat(names(CustomerQuery(filter = CustomerFilter.TO_PAY))).containsExactly("Payable")
        assertThat(names(CustomerQuery(filter = CustomerFilter.SETTLED))).containsExactly("Settled")
        assertThat(names(CustomerQuery(filter = CustomerFilter.DEACTIVATED))).containsExactly("Gone")
        assertThat(names(CustomerQuery(sort = CustomerSort.HIGHEST_PAYABLE)).first()).isEqualTo("Payable")
        assertThat(names(CustomerQuery(sort = CustomerSort.LAST_ACTIVITY)).last()).isEqualTo("Settled")
    }

    @Test
    fun sqlBalanceEqualsKotlinRecomputation() = runTest {
        val id = add("Ram", opening = "500")
        update(id, CustomerDraft(name = "Ram", openingAmount = "750", openingDirection = OpeningDirection.SHOP_OWES, openingDate = today))

        val sql = checkNotNull(customers.observe(id).first()).netBalance
        val kotlin = LedgerCalculator.balance(ledger.observeEntries(id).first())
        assertThat(sql).isEqualTo(kotlin)
        assertThat(sql).isEqualTo(Money.rupees(-750))
    }

    @Test
    fun openingChangeKeepsTheOldRevisionAndAuditsIt() = runTest {
        val id = add("Ram", opening = "500")
        val first = checkNotNull(ledger.activeOpening(id))

        update(id, CustomerDraft(name = "Ram", openingAmount = "650", openingDate = today))

        val revisions = db.ledgerEntryDao().revisions(first.rootId)
        assertThat(revisions.map { it.status }).containsExactly("SUPERSEDED", "ACTIVE").inOrder()
        assertThat(revisions.map { it.referenceNo }.toSet()).containsExactly(first.reference)
        assertThat(revisions.last().supersedesEntryId).isEqualTo(first.id)
        val actions = db.auditLogDao().forCustomer(id).map { it.action }
        assertThat(actions).containsExactly(AuditEvent.ACTION_CREATE, AuditEvent.ACTION_EDIT, AuditEvent.ACTION_OPENING_CHANGE).inOrder()
        assertThat(db.auditLogDao().forCustomer(id).last().beforeJson).contains("\"amount_paise\":\"50000\"")
    }

    @Test
    fun mobileIsUniqueEvenAgainstDeactivatedCustomers() = runTest {
        val ram = add("Ram", mobile = "9876543210")
        activation.set(ram, active = false)

        assertThat(create(CustomerDraft(name = "Shyam", mobile = "09876543210")))
            .isEqualTo(DomainResult.Err(DomainError.DuplicateMobile(ram, "Ram")))
        // The UNIQUE index is the backstop if validation were bypassed.
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking {
                db.customerDao().insert(
                    com.eggbusinessmanager.data.db.entity.CustomerEntity(
                        name = "X", nameNormalized = "x", mobile = "9876543210", address = null, notes = null, createdAt = 0, updatedAt = 0,
                    ),
                )
            }
        }
    }

    @Test
    fun customersWithEntriesCannotBeDeletedEvenDirectly() = runTest {
        val id = add("Ram", opening = "100")

        assertThat(delete(id)).isEqualTo(DomainResult.Err(DomainError.CustomerHasEntries))
        assertThat(db.customerDao().deleteIfUnused(id)).isEqualTo(0)
        // Even a raw DELETE is refused by the ledger foreign key (ON DELETE RESTRICT).
        assertThrows(SQLiteConstraintException::class.java) {
            db.openHelper.writableDatabase.execSQL("DELETE FROM customer WHERE id = $id")
        }
        assertThat(customers.get(id)).isNotNull()
    }

    @Test
    fun unusedCustomerIsDeletedAndTheDeletionIsAudited() = runTest {
        val id = add("Typo")

        assertThat(delete(id)).isEqualTo(DomainResult.Ok(Unit))

        assertThat(customers.get(id)).isNull()
        val audit = db.auditLogDao().forCustomer(id)
        assertThat(audit.map { it.action }).containsExactly(AuditEvent.ACTION_CREATE, AuditEvent.ACTION_DELETE).inOrder()
        assertThat(audit.all { it.customerId == null }).isTrue()
    }

    @Test
    fun ledgerTriggersRejectInvalidRowsUpdatesAndDeletes() = runTest {
        val id = add("Ram", opening = "100")
        val dao = db.ledgerEntryDao()
        fun row(type: String, amount: Long, effect: Long, direction: String? = null) = LedgerEntryEntity(
            rootEntryId = 0, entryType = type, referenceNo = "T-1", status = "ACTIVE", customerId = id,
            entryDate = today.toEpochDay(), amountPaise = amount, balanceEffectPaise = effect, openingDirection = direction, createdAt = 0,
        )

        // Wrong sign for a sale, an opening without direction, a negative amount.
        listOf(row("SELL", 100, -100), row("OPENING_BALANCE", 100, 100), row("BUY", -5, 5)).forEach { bad ->
            assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { dao.insert(bad) } }
        }
        val sql = db.openHelper.writableDatabase
        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL("UPDATE ledger_entry SET amount_paise = 1 WHERE customer_id = $id")
        }
        assertThrows(SQLiteConstraintException::class.java) { sql.execSQL("DELETE FROM ledger_entry WHERE customer_id = $id") }
    }

    @Test
    fun onlyOneActiveRevisionPerEntry() = runTest {
        val id = add("Ram", opening = "100")
        val active = checkNotNull(db.ledgerEntryDao().activeOpening(id))

        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { db.ledgerEntryDao().insert(active.copy(id = 0)) }
        }
    }
}
