package com.eggbusinessmanager.data.repository

import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.data.db.entity.AuditLogEntity
import com.eggbusinessmanager.data.db.entity.LedgerEntryEntity
import com.eggbusinessmanager.data.di.IoDispatcher
import com.eggbusinessmanager.data.mapper.toDomain
import com.eggbusinessmanager.data.mapper.toEntity
import com.eggbusinessmanager.domain.customer.AuditEvent
import com.eggbusinessmanager.domain.customer.Customer
import com.eggbusinessmanager.domain.customer.CustomerQuery
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.customer.OpeningBalance
import com.eggbusinessmanager.domain.customer.ValidCustomer
import com.eggbusinessmanager.domain.ledger.BalanceEffect
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.repository.AuditRepository
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.ledger.EntryValues
import com.eggbusinessmanager.domain.repository.CustomerRepository
import com.eggbusinessmanager.domain.repository.LedgerRepository
import com.eggbusinessmanager.domain.validation.SearchKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider

/** Customers with SQL-computed balances (data-model.md § Derived read models). */
class RoomCustomerRepository @Inject constructor(
    private val database: Provider<EggBusinessDatabase>,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CustomerRepository {

    private val dao get() = database.get().customerDao()

    override fun search(query: CustomerQuery): Flow<List<CustomerSummary>> =
        flow { emitAll(dao.search(likeEscape(SearchKey.of(query.text)), SearchKey.digits(query.text), query.filter.name, query.sort.name)) }
            .map { rows -> rows.map { it.toDomain() } }
            .distinctUntilChanged()
            .flowOn(io)

    override fun observe(id: Long): Flow<CustomerSummary?> =
        flow { emitAll(dao.observeSummary(id)) }.map { it?.toDomain() }.distinctUntilChanged().flowOn(io)

    override suspend fun get(id: Long): Customer? = withContext(io) { dao.get(id)?.toDomain() }

    override suspend fun findByMobile(mobile: String): Customer? = withContext(io) { dao.findByMobile(mobile)?.toDomain() }

    override suspend fun nameExists(name: String, excludingId: Long?): Boolean =
        withContext(io) { dao.nameExists(SearchKey.of(name), excludingId) }

    override suspend fun insert(customer: ValidCustomer, at: Instant): Long = withContext(io) { dao.insert(customer.toEntity(at)) }

    override suspend fun update(id: Long, customer: ValidCustomer, at: Instant) = withContext(io) {
        dao.update(id, customer.name, SearchKey.of(customer.name), customer.mobile, customer.address, customer.notes, at.toEpochMilli())
    }

    override suspend fun setActive(id: Long, active: Boolean, at: Instant) = withContext(io) {
        dao.setArchived(id, archived = !active, at = at.toEpochMilli())
    }

    override suspend fun deleteIfUnused(id: Long): Boolean = withContext(io) { dao.deleteIfUnused(id) == 1 }

    private fun likeEscape(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

/** Ledger writes for opening balances now, and the generic entry primitives later stories build on. */
class RoomLedgerRepository @Inject constructor(
    private val database: Provider<EggBusinessDatabase>,
    @IoDispatcher private val io: CoroutineDispatcher,
) : LedgerRepository {

    private val dao get() = database.get().ledgerEntryDao()

    override fun observeEntries(customerId: Long): Flow<List<LedgerEntry>> =
        flow { emitAll(dao.observeCurrent(customerId)) }.map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged().flowOn(io)

    override fun observeRevisions(rootId: Long): Flow<List<LedgerEntry>> =
        flow { emitAll(dao.observeRevisions(rootId)) }.map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged().flowOn(io)

    override suspend fun currentRevision(rootId: Long): LedgerEntry? = withContext(io) { dao.currentRevision(rootId)?.toDomain() }

    override suspend fun referenceOf(rootId: Long): String? = withContext(io) { dao.referenceOf(rootId) }

    override suspend fun findByClientRequest(clientRequestId: String): EntryRef? = withContext(io) {
        dao.byClientRequest(clientRequestId)?.let { EntryRef(it.rootEntryId, it.referenceNo) }
    }

    override suspend fun insertEntry(values: EntryValues, clientRequestId: String?, at: Instant): EntryRef = withContext(io) {
        val reference = nextReference(values.type)
        val id = dao.insert(values.toEntity(rootId = 0, reference = reference, clientRequestId = clientRequestId, at = at))
        dao.makeOwnRoot(id)
        EntryRef(id, reference)
    }

    override suspend fun reviseEntry(current: LedgerEntry, values: EntryValues, at: Instant) = withContext(io) {
        check(dao.supersede(current.id) == 1) { "Entry ${current.id} is no longer active" }
        dao.insert(values.toEntity(current.rootId, current.reference, clientRequestId = null, at = at).copy(supersedesEntryId = current.id))
        Unit
    }

    override suspend fun replaceEntry(current: LedgerEntry, values: EntryValues, at: Instant): EntryRef = withContext(io) {
        val reference = nextReference(values.type)
        val id = dao.insert(values.toEntity(rootId = 0, reference = reference, clientRequestId = null, at = at))
        dao.makeOwnRoot(id)
        check(dao.markReplaced(current.id, id) == 1) { "Entry ${current.id} is no longer active" }
        EntryRef(id, reference)
    }

    override suspend fun voidEntry(current: LedgerEntry, reason: String?, at: Instant) = withContext(io) {
        check(dao.voidEntry(current.id, reason, at.toEpochMilli()) == 1) { "Entry ${current.id} is no longer active" }
    }

    override suspend fun lastPrice(customerId: Long, type: EntryType, varietyId: Long, unitId: Long): Money? =
        withContext(io) { dao.lastPrice(customerId, type.name, varietyId, unitId)?.let(::Money) }

    override suspend fun hasEntries(customerId: Long): Boolean = withContext(io) { dao.hasEntries(customerId) }

    override suspend fun activeOpening(customerId: Long): LedgerEntry? = withContext(io) { dao.activeOpening(customerId)?.toDomain() }

    override suspend fun insertOpening(customerId: Long, opening: OpeningBalance, at: Instant): Long = withContext(io) {
        val type = EntryType.OPENING_BALANCE
        val id = dao.insert(openingRow(customerId, rootId = 0, reference = nextReference(type), opening = opening, at = at))
        dao.makeOwnRoot(id)
        id
    }

    override suspend fun reviseOpening(current: LedgerEntry, opening: OpeningBalance, at: Instant) = withContext(io) {
        check(dao.supersede(current.id) == 1) { "Opening entry ${current.id} is no longer active" }
        dao.insert(
            openingRow(current.customerId, current.rootId, current.reference, opening, at).copy(supersedesEntryId = current.id),
        )
        Unit
    }

    /** Next never-reused reference for [type], e.g. `OB-000001` (research R6). Must run inside the save transaction. */
    private suspend fun nextReference(type: EntryType): String {
        dao.ensureSequence(type.name)
        val value = dao.nextValue(type.name)
        dao.advance(type.name)
        return "%s-%06d".format(type.referencePrefix, value)
    }

    private fun openingRow(customerId: Long, rootId: Long, reference: String, opening: OpeningBalance, at: Instant) = LedgerEntryEntity(
        rootEntryId = rootId,
        entryType = EntryType.OPENING_BALANCE.name,
        referenceNo = reference,
        status = EntryStatus.ACTIVE.name,
        customerId = customerId,
        entryDate = opening.date.toEpochDay(),
        amountPaise = opening.amount.paise,
        balanceEffectPaise = BalanceEffect.of(EntryType.OPENING_BALANCE, opening.amount, opening.direction).paise,
        openingDirection = opening.direction.name,
        createdAt = at.toEpochMilli(),
    )
}

class RoomAuditRepository @Inject constructor(
    private val database: Provider<EggBusinessDatabase>,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AuditRepository {

    override suspend fun append(event: AuditEvent) = withContext(io) {
        database.get().auditLogDao().insert(
            AuditLogEntity(
                entityType = event.entityType,
                entityId = event.entityId,
                customerId = event.customerId,
                action = event.action,
                beforeJson = event.before?.let(::toJson),
                afterJson = event.after?.let(::toJson),
                reason = event.reason,
                at = event.at.toEpochMilli(),
            ),
        )
        Unit
    }

    private fun toJson(fields: Map<String, String?>) = Json.encodeToString(SNAPSHOT, fields)

    private companion object {
        val SNAPSHOT = MapSerializer(String.serializer(), String.serializer().nullable)
    }
}
