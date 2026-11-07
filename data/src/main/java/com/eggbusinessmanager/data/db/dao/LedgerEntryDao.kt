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

    @Query(
        "SELECT * FROM ledger_entry WHERE customer_id = :customerId AND status = 'ACTIVE' " +
            "ORDER BY entry_date, created_at, id",
    )
    fun observeActive(customerId: Long): Flow<List<LedgerEntryEntity>>

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
