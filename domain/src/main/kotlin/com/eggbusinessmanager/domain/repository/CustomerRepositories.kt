package com.eggbusinessmanager.domain.repository

import com.eggbusinessmanager.domain.customer.AuditEvent
import com.eggbusinessmanager.domain.customer.Customer
import com.eggbusinessmanager.domain.customer.CustomerQuery
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.customer.OpeningBalance
import com.eggbusinessmanager.domain.customer.ValidCustomer
import com.eggbusinessmanager.domain.ledger.LedgerEntry
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

/** All money-moving writes and ledger reads (contracts/domain-interfaces.md § LedgerRepository). */
interface LedgerRepository {
    /** Active revisions of every entry of a customer. */
    fun observeEntries(customerId: Long): Flow<List<LedgerEntry>>

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
