package com.eggbusinessmanager.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.eggbusinessmanager.data.db.entity.CustomerEntity
import kotlinx.coroutines.flow.Flow

/** A customer with its balance (Σ active balance effects) and last activity (max active entry date). */
data class CustomerSummaryRow(
    @Embedded val customer: CustomerEntity,
    @ColumnInfo(name = "net_balance_paise") val netBalancePaise: Long,
    @ColumnInfo(name = "last_activity_day") val lastActivityDay: Long?,
)

@Dao
interface CustomerDao {

    /**
     * Search-as-you-type list (FR-017/018). [text] is already in search-key form and LIKE-escaped with
     * `\`; [digits] matches anywhere in the mobile number. Balances count ACTIVE revisions only.
     */
    @Query(
        """
        SELECT c.*, COALESCE(b.balance, 0) AS net_balance_paise, b.last_activity AS last_activity_day
        FROM customer c
        LEFT JOIN (
            SELECT customer_id, SUM(balance_effect_paise) AS balance, MAX(entry_date) AS last_activity
            FROM ledger_entry WHERE status = 'ACTIVE' GROUP BY customer_id
        ) b ON b.customer_id = c.id
        WHERE (:text = '' OR c.name_normalized LIKE '%' || :text || '%' ESCAPE '\'
               OR (:digits <> '' AND c.mobile LIKE '%' || :digits || '%'))
          AND CASE :filter
                WHEN 'ACTIVE' THEN c.is_archived = 0
                WHEN 'TO_RECEIVE' THEN c.is_archived = 0 AND COALESCE(b.balance, 0) > 0
                WHEN 'TO_PAY' THEN c.is_archived = 0 AND COALESCE(b.balance, 0) < 0
                WHEN 'SETTLED' THEN c.is_archived = 0 AND COALESCE(b.balance, 0) = 0
                WHEN 'DEACTIVATED' THEN c.is_archived = 1
                ELSE 0
              END
        ORDER BY
            CASE WHEN :sort = 'HIGHEST_RECEIVABLE' THEN COALESCE(b.balance, 0) END DESC,
            CASE WHEN :sort = 'HIGHEST_PAYABLE' THEN COALESCE(b.balance, 0) END ASC,
            CASE WHEN :sort = 'LAST_ACTIVITY' THEN b.last_activity IS NULL END ASC,
            CASE WHEN :sort = 'LAST_ACTIVITY' THEN b.last_activity END DESC,
            c.name_normalized ASC,
            c.id ASC
        """,
    )
    fun search(text: String, digits: String, filter: String, sort: String): Flow<List<CustomerSummaryRow>>

    @Query(
        """
        SELECT c.*,
            (SELECT COALESCE(SUM(balance_effect_paise), 0) FROM ledger_entry
                WHERE customer_id = c.id AND status = 'ACTIVE') AS net_balance_paise,
            (SELECT MAX(entry_date) FROM ledger_entry
                WHERE customer_id = c.id AND status = 'ACTIVE') AS last_activity_day
        FROM customer c WHERE c.id = :id
        """,
    )
    fun observeSummary(id: Long): Flow<CustomerSummaryRow?>

    /** Every customer, deactivated included, with balance and last activity. */
    @Query(
        """
        SELECT c.*, COALESCE(b.balance, 0) AS net_balance_paise, b.last_activity AS last_activity_day
        FROM customer c
        LEFT JOIN (
            SELECT customer_id, SUM(balance_effect_paise) AS balance, MAX(entry_date) AS last_activity
            FROM ledger_entry WHERE status = 'ACTIVE' GROUP BY customer_id
        ) b ON b.customer_id = c.id
        """,
    )
    fun allSummaries(): Flow<List<CustomerSummaryRow>>

    @Query("SELECT * FROM customer WHERE id = :id")
    suspend fun get(id: Long): CustomerEntity?

    @Query("SELECT * FROM customer WHERE mobile = :mobile LIMIT 1")
    suspend fun findByMobile(mobile: String): CustomerEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM customer WHERE name_normalized = :key AND (:excludingId IS NULL OR id <> :excludingId))")
    suspend fun nameExists(key: String, excludingId: Long?): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(customer: CustomerEntity): Long

    @Query(
        """
        UPDATE customer SET name = :name, name_normalized = :nameNormalized, mobile = :mobile,
            address = :address, notes = :notes, updated_at = :at
        WHERE id = :id
        """,
    )
    suspend fun update(id: Long, name: String, nameNormalized: String, mobile: String?, address: String?, notes: String?, at: Long)

    @Query("UPDATE customer SET is_archived = :archived, updated_at = :at WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, at: Long)

    /** Deletes only when no ledger row references the customer; returns the number of rows deleted. */
    @Query("DELETE FROM customer WHERE id = :id AND NOT EXISTS (SELECT 1 FROM ledger_entry WHERE customer_id = :id)")
    suspend fun deleteIfUnused(id: Long): Int
}
