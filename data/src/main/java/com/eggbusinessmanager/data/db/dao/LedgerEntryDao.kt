package com.eggbusinessmanager.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.eggbusinessmanager.data.db.entity.AuditLogEntity
import com.eggbusinessmanager.data.db.entity.LedgerEntryEntity
import kotlinx.coroutines.flow.Flow

/** Write primitives and reads for `ledger_entry`. Callers run multi-step writes in one transaction. */
@Dao
interface LedgerEntryDao {

    /** Current revisions of a customer's entries: ACTIVE and deleted (VOIDED); superseded rows are history only. */
    @Query(
        "SELECT * FROM ledger_entry WHERE customer_id = :customerId AND status <> 'SUPERSEDED' " +
            "ORDER BY entry_date, created_at, id",
    )
    fun observeCurrent(customerId: Long): Flow<List<LedgerEntryEntity>>

    @Query("SELECT * FROM ledger_entry WHERE root_entry_id = :rootId ORDER BY id")
    fun observeRevisions(rootId: Long): Flow<List<LedgerEntryEntity>>

    @Query("SELECT * FROM ledger_entry WHERE root_entry_id = :rootId ORDER BY id DESC LIMIT 1")
    suspend fun currentRevision(rootId: Long): LedgerEntryEntity?

    @Query("SELECT reference_no FROM ledger_entry WHERE root_entry_id = :rootId LIMIT 1")
    suspend fun referenceOf(rootId: Long): String?

    @Query("SELECT * FROM ledger_entry WHERE client_request_id = :clientRequestId LIMIT 1")
    suspend fun byClientRequest(clientRequestId: String): LedgerEntryEntity?

    /** FR-024: the most recent active price for this customer, type, variety and unit. */
    @Query(
        "SELECT price_per_unit_paise FROM ledger_entry WHERE customer_id = :customerId AND entry_type = :type " +
            "AND variety_id = :varietyId AND unit_id = :unitId AND status = 'ACTIVE' " +
            "ORDER BY entry_date DESC, created_at DESC, id DESC LIMIT 1",
    )
    suspend fun lastPrice(customerId: Long, type: String, varietyId: Long, unitId: Long): Long?

    /** Deleting is voiding (FR-034): the row stays, labelled, and stops counting. */
    @Query("UPDATE ledger_entry SET status = 'VOIDED', void_reason = :reason, voided_at = :at WHERE id = :id AND status = 'ACTIVE'")
    suspend fun voidEntry(id: Long, reason: String?, at: Long): Int

    /** Type change (FR-033b): the old entry's last revision points at the entry that replaced it. */
    @Query(
        "UPDATE ledger_entry SET status = 'SUPERSEDED', replaced_by_root_id = :newRootId " +
            "WHERE id = :id AND status = 'ACTIVE'",
    )
    suspend fun markReplaced(id: Long, newRootId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM ledger_entry WHERE customer_id = :customerId)")
    suspend fun hasEntries(customerId: Long): Boolean

    @Query(
        "SELECT * FROM ledger_entry WHERE customer_id = :customerId AND entry_type = 'OPENING_BALANCE' " +
            "AND status = 'ACTIVE' LIMIT 1",
    )
    suspend fun activeOpening(customerId: Long): LedgerEntryEntity?

    @Query("SELECT * FROM ledger_entry WHERE root_entry_id = :rootId ORDER BY id")
    suspend fun revisions(rootId: Long): List<LedgerEntryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: LedgerEntryEntity): Long

    /** A first revision is its own root; set right after insert, inside the same transaction. */
    @Query("UPDATE ledger_entry SET root_entry_id = id WHERE id = :id")
    suspend fun makeOwnRoot(id: Long)

    @Query("UPDATE ledger_entry SET status = 'SUPERSEDED' WHERE id = :id AND status = 'ACTIVE'")
    suspend fun supersede(id: Long): Int

    // ---- reference_sequence (research R6) ----

    @Query("INSERT OR IGNORE INTO reference_sequence (entry_type, next_value) VALUES (:type, 1)")
    suspend fun ensureSequence(type: String)

    @Query("SELECT next_value FROM reference_sequence WHERE entry_type = :type")
    suspend fun nextValue(type: String): Long

    @Query("UPDATE reference_sequence SET next_value = next_value + 1 WHERE entry_type = :type")
    suspend fun advance(type: String)
}

@Dao
interface AuditLogDao {
    @Insert
    suspend fun insert(row: AuditLogEntity): Long

    @Query(
        "SELECT * FROM audit_log WHERE customer_id = :customerId " +
            "OR (entity_type = 'CUSTOMER' AND entity_id = :customerId) ORDER BY at, id",
    )
    suspend fun forCustomer(customerId: Long): List<AuditLogEntity>
}
