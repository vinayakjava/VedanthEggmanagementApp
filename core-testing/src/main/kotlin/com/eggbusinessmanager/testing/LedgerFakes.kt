package com.eggbusinessmanager.testing

import com.eggbusinessmanager.domain.catalog.EggVariety
import com.eggbusinessmanager.domain.catalog.MeasurementUnit
import com.eggbusinessmanager.domain.customer.OpeningBalance
import com.eggbusinessmanager.domain.ledger.BalanceEffect
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.ledger.TradeLine
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.repository.CatalogRepository
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.repository.LedgerRepository
import com.eggbusinessmanager.domain.trade.ValidTrade
import com.eggbusinessmanager.domain.validation.SearchKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

/** In-memory ledger with the same revision semantics as Room (one ACTIVE row per root). */
class FakeLedgerRepository : LedgerRepository {
    val rows = MutableStateFlow<List<LedgerEntry>>(emptyList())
    private val clientRequests = mutableMapOf<String, EntryRef>()
    private var nextId = 1L
    private val nextReference = mutableMapOf<EntryType, Int>()

    private fun reference(type: EntryType): String {
        val value = nextReference.getOrDefault(type, 1)
        nextReference[type] = value + 1
        return type.referencePrefix + "-" + value.toString().padStart(6, '0')
    }

    override fun observeEntries(customerId: Long): Flow<List<LedgerEntry>> =
        rows.map { all -> all.filter { it.status != EntryStatus.SUPERSEDED && it.customerId == customerId } }

    override fun observeRevisions(rootId: Long): Flow<List<LedgerEntry>> =
        rows.map { all -> all.filter { it.rootId == rootId }.sortedBy { it.id } }

    override suspend fun currentRevision(rootId: Long): LedgerEntry? = rows.value.filter { it.rootId == rootId }.maxByOrNull { it.id }

    override suspend fun referenceOf(rootId: Long): String? = rows.value.firstOrNull { it.rootId == rootId }?.reference

    override suspend fun findByClientRequest(clientRequestId: String): EntryRef? = clientRequests[clientRequestId]

    override suspend fun insertTrade(trade: ValidTrade, clientRequestId: String?, at: Instant): EntryRef {
        val id = nextId++
        val ref = EntryRef(id, reference(trade.type))
        rows.value += trade.toEntry(id, id, ref.reference, at)
        if (clientRequestId != null) clientRequests[clientRequestId] = ref
        return ref
    }

    override suspend fun reviseTrade(current: LedgerEntry, trade: ValidTrade, at: Instant) {
        val id = nextId++
        supersede(current.id)
        rows.value += trade.toEntry(id, current.rootId, current.reference, at).copy(supersedesId = current.id)
    }

    override suspend fun replaceTrade(current: LedgerEntry, trade: ValidTrade, at: Instant): EntryRef {
        val id = nextId++
        val ref = EntryRef(id, reference(trade.type))
        rows.value = rows.value.map {
            if (it.id == current.id) it.copy(status = EntryStatus.SUPERSEDED, replacedByRootId = id) else it
        } + trade.toEntry(id, id, ref.reference, at)
        return ref
    }

    override suspend fun voidEntry(current: LedgerEntry, reason: String?, at: Instant) {
        rows.value = rows.value.map {
            if (it.id == current.id) it.copy(status = EntryStatus.VOIDED, voidReason = reason, voidedAt = at) else it
        }
    }

    override suspend fun lastPrice(customerId: Long, type: EntryType, varietyId: Long, unitId: Long): Money? =
        rows.value.filter {
            it.status == EntryStatus.ACTIVE && it.customerId == customerId && it.type == type &&
                it.trade?.varietyId == varietyId && it.trade?.unitId == unitId
        }.maxWithOrNull(compareBy<LedgerEntry>({ it.date }, { it.createdAt }, { it.id }))?.trade?.pricePerUnit

    override suspend fun hasEntries(customerId: Long): Boolean = rows.value.any { it.customerId == customerId }

    override suspend fun activeOpening(customerId: Long): LedgerEntry? =
        rows.value.firstOrNull { it.customerId == customerId && it.type == EntryType.OPENING_BALANCE && it.status == EntryStatus.ACTIVE }

    override suspend fun insertOpening(customerId: Long, opening: OpeningBalance, at: Instant): Long {
        val id = nextId++
        rows.value += openingEntry(id, id, reference(EntryType.OPENING_BALANCE), customerId, opening, at)
        return id
    }

    override suspend fun reviseOpening(current: LedgerEntry, opening: OpeningBalance, at: Instant) {
        val id = nextId++
        supersede(current.id)
        rows.value += openingEntry(id, current.rootId, current.reference, current.customerId, opening, at).copy(supersedesId = current.id)
    }

    /** Adds any other entry type directly (payments arrive with a later story). */
    fun addEntry(customerId: Long, type: EntryType, amount: Money, date: LocalDate, at: Instant) {
        val id = nextId++
        rows.value += LedgerEntry(
            id, id, reference(type), type, EntryStatus.ACTIVE, customerId, date, amount,
            BalanceEffect.of(type, amount), null, null, at,
        )
    }

    private fun supersede(id: Long) {
        rows.value = rows.value.map { if (it.id == id) it.copy(status = EntryStatus.SUPERSEDED) else it }
    }

    private fun ValidTrade.toEntry(id: Long, root: Long, reference: String, at: Instant) = LedgerEntry(
        id = id, rootId = root, reference = reference, type = type, status = EntryStatus.ACTIVE, customerId = customerId,
        date = date, amount = total, balanceEffect = BalanceEffect.of(type, total), openingDirection = null,
        remarks = remarks, createdAt = at,
        trade = TradeLine(varietyId, unitId, quantity, piecesPerUnit, pieces, pricePerUnit),
    )

    private fun openingEntry(id: Long, root: Long, reference: String, customerId: Long, opening: OpeningBalance, at: Instant) =
        LedgerEntry(
            id = id, rootId = root, reference = reference, type = EntryType.OPENING_BALANCE, status = EntryStatus.ACTIVE,
            customerId = customerId, date = opening.date, amount = opening.amount,
            balanceEffect = BalanceEffect.of(EntryType.OPENING_BALANCE, opening.amount, opening.direction),
            openingDirection = opening.direction, remarks = null, createdAt = at,
        )
}

/** In-memory units and varieties seeded like the database (FR-059, FR-027a). */
class FakeCatalogRepository : CatalogRepository {
    val units = MutableStateFlow(
        listOf(
            MeasurementUnit(PIECE, "Piece", 1, isActive = true, isSystem = true),
            MeasurementUnit(DOZEN, "Dozen", 12, isActive = true, isSystem = false),
            MeasurementUnit(TRAY, "Tray", 30, isActive = true, isSystem = false),
            MeasurementUnit(BOX, "Box", 210, isActive = true, isSystem = false),
        ),
    )
    val varieties = MutableStateFlow(
        listOf(
            EggVariety(WHITE, "White", isActive = true, isDefault = true),
            EggVariety(BROWN, "Brown", isActive = true, isDefault = false),
            EggVariety(COUNTRY, "Country (Desi)", isActive = true, isDefault = false),
        ),
    )

    override fun observeUnits(includeInactive: Boolean): Flow<List<MeasurementUnit>> =
        units.map { list -> list.filter { includeInactive || it.isActive } }

    override fun observeVarieties(includeInactive: Boolean): Flow<List<EggVariety>> =
        varieties.map { list -> list.filter { includeInactive || it.isActive } }

    override suspend fun unit(id: Long): MeasurementUnit? = units.value.firstOrNull { it.id == id }

    override suspend fun variety(id: Long): EggVariety? = varieties.value.firstOrNull { it.id == id }

    override suspend fun unitNameExists(name: String, excludingId: Long?): Boolean =
        units.value.any { it.id != excludingId && SearchKey.of(it.name) == SearchKey.of(name) }

    override suspend fun varietyNameExists(name: String, excludingId: Long?): Boolean =
        varieties.value.any { it.id != excludingId && SearchKey.of(it.name) == SearchKey.of(name) }

    override suspend fun insertUnit(name: String, piecesPerUnit: Int): Long {
        val id = (units.value.maxOfOrNull { it.id } ?: 0) + 1
        units.value += MeasurementUnit(id, name, piecesPerUnit, isActive = true, isSystem = false)
        return id
    }

    override suspend fun updateUnit(id: Long, name: String, piecesPerUnit: Int) {
        units.value = units.value.map { if (it.id == id) it.copy(name = name, piecesPerUnit = piecesPerUnit) else it }
    }

    override suspend fun setUnitActive(id: Long, active: Boolean) {
        units.value = units.value.map { if (it.id == id) it.copy(isActive = active) else it }
    }

    override suspend fun insertVariety(name: String): Long {
        val id = (varieties.value.maxOfOrNull { it.id } ?: 0) + 1
        varieties.value += EggVariety(id, name, isActive = true, isDefault = false)
        return id
    }

    override suspend fun renameVariety(id: Long, name: String) {
        varieties.value = varieties.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    override suspend fun setVarietyActive(id: Long, active: Boolean) {
        varieties.value = varieties.value.map { if (it.id == id) it.copy(isActive = active) else it }
    }

    override suspend fun setDefaultVariety(id: Long) {
        varieties.value = varieties.value.map { it.copy(isDefault = it.id == id) }
    }

    companion object {
        const val PIECE = 1L
        const val DOZEN = 2L
        const val TRAY = 3L
        const val BOX = 4L
        const val WHITE = 1L
        const val BROWN = 2L
        const val COUNTRY = 3L
    }
}
