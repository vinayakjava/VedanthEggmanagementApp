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
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerCalculator
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.trade.TradeDraft
import com.eggbusinessmanager.domain.usecase.catalog.SaveUnitUseCase
import com.eggbusinessmanager.domain.usecase.catalog.SetUnitActiveUseCase
import com.eggbusinessmanager.domain.usecase.catalog.SetVarietyActiveUseCase
import com.eggbusinessmanager.domain.usecase.customer.CreateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.CustomerChecks
import com.eggbusinessmanager.domain.usecase.customer.DeleteCustomerUseCase
import com.eggbusinessmanager.domain.usecase.trade.DeleteEntryUseCase
import com.eggbusinessmanager.domain.usecase.trade.EditTradeUseCase
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

/** Sales and purchases on the real encrypted database (US3, US5 corrections, US12 units). */
@RunWith(AndroidJUnit4::class)
class TradeRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileName = "trade-repo-test.db"
    private lateinit var db: EggBusinessDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var catalog: RoomCatalogRepository
    private lateinit var customers: RoomCustomerRepository
    private lateinit var record: RecordTradeUseCase
    private lateinit var edit: EditTradeUseCase
    private lateinit var delete: DeleteEntryUseCase
    private lateinit var createCustomer: CreateCustomerUseCase
    private lateinit var deleteCustomer: DeleteCustomerUseCase
    private lateinit var saveUnit: SaveUnitUseCase

    private val today = LocalDate.of(2026, 11, 8)
    private val clock = object : Clock {
        var instant: Instant = Instant.parse("2026-11-08T10:00:00Z")
        override fun now(): Instant = instant.also { instant = instant.plusSeconds(1) }
        override fun today(): LocalDate = today
    }

    // Seeded ids (SchemaCallback): units Piece 1, Dozen 2, Tray 3, Box 4; varieties White 1, Brown 2, Country 3.
    private val piece = 1L
    private val tray = 3L
    private val box = 4L
    private val brown = 2L

    @Before
    fun setUp() {
        context.deleteDatabase(fileName)
        db = DatabaseFactory.build(context, ByteArray(32).also { SecureRandom().nextBytes(it) }, fileName)
        val provider = Provider { db }
        ledger = RoomLedgerRepository(provider, Dispatchers.IO)
        catalog = RoomCatalogRepository(provider, Dispatchers.IO)
        customers = RoomCustomerRepository(provider, Dispatchers.IO)
        val audit = RoomAuditRepository(provider, Dispatchers.IO)
        val tx = RoomTransactionRunner(provider)
        val checks = TradeChecks(customers, catalog, clock)
        record = RecordTradeUseCase(checks, ledger, audit, tx, clock)
        edit = EditTradeUseCase(checks, ledger, audit, tx, clock)
        delete = DeleteEntryUseCase(ledger, audit, tx, clock)
        createCustomer = CreateCustomerUseCase(CustomerChecks(customers, clock), customers, ledger, audit, tx, clock)
        deleteCustomer = DeleteCustomerUseCase(customers, ledger, audit, tx, clock)
        saveUnit = SaveUnitUseCase(catalog, audit, tx, clock)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(fileName)
    }

    private suspend fun customer(name: String) = (createCustomer(CustomerDraft(name = name)) as DomainResult.Ok).value

    private fun sale(customerId: Long, qty: String = "10", price: String = "180", unit: Long = tray) =
        TradeDraft(EntryType.SELL, customerId, today, brown, unit, qty, price)

    private suspend fun recorded(draft: TradeDraft, request: String = "req-" + System.nanoTime()): EntryRef =
        (record(draft, request) as DomainResult.Ok).value

    private suspend fun sqlBalance(customerId: Long) = checkNotNull(customers.observe(customerId).first()).netBalance

    @Test
    fun seededUnitsAndVarietiesHaveTheirOwnConversions() = runTest {
        assertThat(catalog.observeUnits(includeInactive = true).first().map { it.name to it.piecesPerUnit })
            .containsExactly("Piece" to 1, "Dozen" to 12, "Tray" to 30, "Box" to 210).inOrder()
        assertThat(catalog.unit(piece)?.isSystem).isTrue()
        val varieties = catalog.observeVarieties(includeInactive = true).first()
        assertThat(varieties.map { it.name }).containsExactly("White", "Brown", "Country (Desi)").inOrder()
        assertThat(varieties.single { it.isDefault }.name).isEqualTo("White")
    }

    @Test
    fun saleAndPurchaseUpdateTheLedgerWithTheirOwnReferenceSeries() = runTest {
        val ram = customer("Ram")

        val sold = recorded(sale(ram))
        val bought = recorded(sale(ram, qty = "2", price = "1000", unit = box).copy(type = EntryType.BUY))

        assertThat(sold.reference).isEqualTo("S-000001")
        assertThat(bought.reference).isEqualTo("B-000001")
        assertThat(sqlBalance(ram)).isEqualTo(Money.rupees(1_800 - 2_000))
        val rows = ledger.observeEntries(ram).first()
        assertThat(rows.map { it.trade?.pieces }).containsExactly(300L, 420L)
        assertThat(sqlBalance(ram)).isEqualTo(LedgerCalculator.balance(rows))
    }

    @Test
    fun theSameSaveRequestIsStoredOnce() = runTest {
        val ram = customer("Ram")
        val first = record(sale(ram), "double-tap")
        val second = record(sale(ram), "double-tap")

        assertThat(second).isEqualTo(first)
        assertThat(ledger.observeEntries(ram).first()).hasSize(1)
        // The UNIQUE client_request_id index is the backstop.
        val row = checkNotNull(db.ledgerEntryDao().byClientRequest("double-tap"))
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.ledgerEntryDao().insert(row.copy(id = 0, rootEntryId = 0, referenceNo = "S-999999")) }
        }
    }

    @Test
    fun editingKeepsHistoryAndChangingCustomerMovesBothBalances() = runTest {
        val x = customer("Xavier")
        val y = customer("Yusuf")
        val ref = recorded(sale(x))

        edit(ref.rootId, sale(x, qty = "12"), "recount")
        edit(ref.rootId, sale(y, qty = "12"))

        assertThat(sqlBalance(x)).isEqualTo(Money.ZERO)
        assertThat(sqlBalance(y)).isEqualTo(Money.rupees(2_160))
        val revisions = ledger.observeRevisions(ref.rootId).first()
        assertThat(revisions.map { it.status })
            .containsExactly(EntryStatus.SUPERSEDED, EntryStatus.SUPERSEDED, EntryStatus.ACTIVE).inOrder()
        assertThat(revisions.map { it.reference }.toSet()).containsExactly("S-000001")
        assertThat(revisions.map { it.customerId }).containsExactly(x, x, y).inOrder()
        assertThat(db.auditLogDao().forCustomer(x).map { it.action }).containsAtLeast("CREATE", "EDIT", "CUSTOMER_CHANGE")
        assertThat(db.auditLogDao().forCustomer(y).map { it.action }).contains("CUSTOMER_CHANGE")
    }

    @Test
    fun sellChangedToBuyGetsANewReferenceAndPointsBack() = runTest {
        val ram = customer("Ram")
        val sold = recorded(sale(ram))

        val bought = (edit(sold.rootId, sale(ram).copy(type = EntryType.BUY)) as DomainResult.Ok).value

        assertThat(bought.reference).isEqualTo("B-000001")
        assertThat(sqlBalance(ram)).isEqualTo(Money.rupees(-1_800))
        val old = checkNotNull(ledger.currentRevision(sold.rootId))
        assertThat(old.status).isEqualTo(EntryStatus.SUPERSEDED)
        assertThat(old.replacedByRootId).isEqualTo(bought.rootId)
        assertThat(ledger.referenceOf(old.replacedByRootId!!)).isEqualTo("B-000001")
    }

    @Test
    fun deletedEntriesStayButStopCounting() = runTest {
        val ram = customer("Ram")
        val ref = recorded(sale(ram))

        delete(ref.rootId, "duplicate")

        assertThat(sqlBalance(ram)).isEqualTo(Money.ZERO)
        val shown = ledger.observeEntries(ram).first().single()
        assertThat(shown.status).isEqualTo(EntryStatus.VOIDED)
        assertThat(shown.voidReason).isEqualTo("duplicate")
        assertThat(edit(ref.rootId, sale(ram, qty = "1"))).isEqualTo(DomainResult.Err(DomainError.EntryNotEditable))
        // A customer with only a deleted entry still cannot be deleted: history must stay intact.
        assertThat(deleteCustomer(ram)).isEqualTo(DomainResult.Err(DomainError.CustomerHasEntries))
    }

    @Test
    fun resizingAUnitNeverChangesPastEntries() = runTest {
        val ram = customer("Ram")
        val before = recorded(sale(ram, qty = "2", price = "1000", unit = box))

        saveUnit(box, "Box", "180")
        val after = recorded(sale(ram, qty = "2", price = "1000", unit = box))

        assertThat(ledger.currentRevision(before.rootId)?.trade?.pieces).isEqualTo(420L)
        assertThat(ledger.currentRevision(after.rootId)?.trade?.pieces).isEqualTo(360L)
        assertThat(catalog.unit(box)?.piecesPerUnit).isEqualTo(180)
    }

    @Test
    fun lastPriceIsTheMostRecentActiveMatch() = runTest {
        val ram = customer("Ram")
        recorded(sale(ram, price = "175"))
        val latest = recorded(sale(ram, price = "182.50"))
        assertThat(ledger.lastPrice(ram, EntryType.SELL, brown, tray)).isEqualTo(Money.rupees(182, 50))

        delete(latest.rootId)
        assertThat(ledger.lastPrice(ram, EntryType.SELL, brown, tray)).isEqualTo(Money.rupees(175))
        assertThat(ledger.lastPrice(ram, EntryType.BUY, brown, tray)).isNull()
    }

    @Test
    fun usedUnitsAndVarietiesCannotBeDeletedOnlyDeactivated() = runTest {
        val ram = customer("Ram")
        recorded(sale(ram))
        val sql = db.openHelper.writableDatabase

        assertThrows(SQLiteConstraintException::class.java) { sql.execSQL("DELETE FROM measurement_unit WHERE id = $tray") }
        assertThrows(SQLiteConstraintException::class.java) { sql.execSQL("DELETE FROM egg_variety WHERE id = $brown") }
        SetUnitActiveUseCase(catalog)(tray, false)
        assertThat(catalog.observeUnits(includeInactive = false).first().map { it.id }).doesNotContain(tray)
        assertThat(ledger.observeEntries(ram).first().single().trade?.unitId).isEqualTo(tray)
        SetVarietyActiveUseCase(catalog, RoomTransactionRunner(Provider { db }))(brown, false)
        assertThat(catalog.variety(brown)?.isActive).isFalse()
    }

    @Test
    fun pieceIsLockedEvenAtTheDatabase() = runTest {
        db.catalogDao().updateUnit(piece, "Egg", "egg", 2)
        db.catalogDao().setUnitActive(piece, false)
        val unit = checkNotNull(catalog.unit(piece))
        assertThat(unit.name).isEqualTo("Piece")
        assertThat(unit.piecesPerUnit).isEqualTo(1)
        assertThat(unit.isActive).isTrue()
    }

    @Test
    fun triggersRejectInconsistentTradeRows() = runTest {
        val ram = customer("Ram")
        val dao = db.ledgerEntryDao()
        fun row(pieces: Long?, price: Long?, effect: Long = 180_000, variety: Long? = brown, mode: String? = null) = LedgerEntryEntity(
            rootEntryId = 0, entryType = "SELL", referenceNo = "S-T", status = "ACTIVE", customerId = ram, entryDate = today.toEpochDay(),
            amountPaise = 180_000, balanceEffectPaise = effect, varietyId = variety, unitId = tray, quantityMilli = 10_000,
            piecesPerUnitSnapshot = 30, pieces = pieces, pricePerUnitPaise = price, paymentMode = mode, createdAt = 0,
        )
        listOf(
            row(pieces = 299, price = 18_000), // pieces ≠ quantity × unit size
            row(pieces = 300, price = null), // missing price
            row(pieces = 300, price = 18_000, variety = null), // missing variety
            row(pieces = 300, price = 18_000, effect = -180_000), // wrong sign for a sale
            row(pieces = 300, price = 18_000, mode = "CASH"), // a trade has no payment mode
        ).forEach { bad -> assertThrows(SQLiteConstraintException::class.java) { runBlocking { dao.insert(bad) } } }

        val good = dao.insert(row(pieces = 300, price = 18_000))
        val sql = db.openHelper.writableDatabase
        assertThrows(SQLiteConstraintException::class.java) { sql.execSQL("UPDATE ledger_entry SET pieces = 301 WHERE id = $good") }
        assertThrows(SQLiteConstraintException::class.java) { sql.execSQL("UPDATE ledger_entry SET remarks = 'x' WHERE id = $good") }
    }
}
