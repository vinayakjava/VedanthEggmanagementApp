package com.eggbusinessmanager.domain.repository

import com.eggbusinessmanager.domain.customer.AuditEvent
import com.eggbusinessmanager.domain.customer.Customer
import com.eggbusinessmanager.domain.customer.CustomerQuery
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.customer.OpeningBalance
import com.eggbusinessmanager.domain.customer.ValidCustomer
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.trade.ValidTrade
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Customers and their derived balances (contracts/domain-interfaces.md § CustomerRepository). */
interface CustomerRepository {
    fun search(query: CustomerQuery): Flow<List<CustomerSummary>>

    fun observe(id: Long): Flow<CustomerSummary?>

    suspend fun get(id: Long): Customer?

    suspend fun findByMobile(mobile: String): Customer?

    /** True when another customer has the same name in search form (duplicate-name warning). */
    suspend fun nameExists(name: String, excludingId: Long?): Boolean

    /** Returns the new id. Not transactional by itself; callers wrap it with [TransactionRunner]. */
    suspend fun insert(customer: ValidCustomer, at: Instant): Long

    suspend fun update(id: Long, customer: ValidCustomer, at: Instant)

    suspend fun setActive(id: Long, active: Boolean, at: Instant)

    /** Deletes a customer with no ledger rows. False (nothing deleted) when entries exist. */
    suspend fun deleteIfUnused(id: Long): Boolean
}

/** A logical entry's identity: its root id and reference number (e.g. S-000001). */
data class EntryRef(val rootId: Long, val reference: String)

/** All money-moving writes and ledger reads (contracts/domain-interfaces.md § LedgerRepository). */
interface LedgerRepository {
    /**
     * The current revision of every entry of a customer: ACTIVE ones and deleted (VOIDED) ones, which
     * stay visible but never count. Superseded revisions are excluded.
     */
    fun observeEntries(customerId: Long): Flow<List<LedgerEntry>>

    /** Every revision of one logical entry, oldest first (FR-033 history). */
    fun observeRevisions(rootId: Long): Flow<List<LedgerEntry>>

    /** The current revision of a logical entry: ACTIVE, or the VOIDED/SUPERSEDED last one. */
    suspend fun currentRevision(rootId: Long): LedgerEntry?

    suspend fun referenceOf(rootId: Long): String?

    /** The root created by an earlier save with the same [clientRequestId] (double-tap protection, R6). */
    suspend fun findByClientRequest(clientRequestId: String): EntryRef?

    /** Creates a SELL/BUY with the next reference in its series. */
    suspend fun insertTrade(trade: ValidTrade, clientRequestId: String?, at: Instant): EntryRef

    /** Supersedes [current] with [trade] of the same type: same root and reference (FR-033). */
    suspend fun reviseTrade(current: LedgerEntry, trade: ValidTrade, at: Instant)

    /** Type change (FR-033b): a new entry in the new type's series replaces [current]. */
    suspend fun replaceTrade(current: LedgerEntry, trade: ValidTrade, at: Instant): EntryRef

    /** Marks [current] VOIDED ("Deleted"): kept, labelled, excluded from every figure (FR-034). */
    suspend fun voidEntry(current: LedgerEntry, reason: String?, at: Instant)

    /** Last active price for this customer, type, variety and unit (FR-024). */
    suspend fun lastPrice(customerId: Long, type: EntryType, varietyId: Long, unitId: Long): Money?

    suspend fun hasEntries(customerId: Long): Boolean

    /** The current (ACTIVE) opening-balance entry, if any. */
    suspend fun activeOpening(customerId: Long): LedgerEntry?

    /** Creates an OPENING_BALANCE entry with the next OB- reference. Returns its root id. */
    suspend fun insertOpening(customerId: Long, opening: OpeningBalance, at: Instant): Long

    /** Supersedes [current] with a new revision holding [opening] (same root and reference, FR-019). */
    suspend fun reviseOpening(current: LedgerEntry, opening: OpeningBalance, at: Instant)
}

/** Append-only audit trail. Always called inside the transaction of the change it records. */
interface AuditRepository {
    suspend fun append(event: AuditEvent)
}
