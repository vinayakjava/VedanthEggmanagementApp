package com.eggbusinessmanager.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import com.eggbusinessmanager.data.db.entity.LedgerEntryEntity
import kotlinx.coroutines.flow.Flow

data class TransactionRowEntity(
    @Embedded val entry: LedgerEntryEntity,
    @ColumnInfo(name = "customer_name") val customerName: String,
    @ColumnInfo(name = "customer_mobile") val customerMobile: String?,
)

data class CustomerCountRow(
    @ColumnInfo(name = "total") val total: Int,
    @ColumnInfo(name = "active") val active: Int,
)

data class TradeTotalRow(
    @ColumnInfo(name = "entry_type") val entryType: String,
    @ColumnInfo(name = "count") val count: Int,
    @ColumnInfo(name = "eggs") val eggs: Long,
    @ColumnInfo(name = "value") val valuePaise: Long,
)

data class DuesRow(
    @ColumnInfo(name = "receivable") val receivablePaise: Long,
    @ColumnInfo(name = "payable") val payablePaise: Long,
    @ColumnInfo(name = "receivable_count") val receivableCount: Int,
    @ColumnInfo(name = "payable_count") val payableCount: Int,
)

/**
 * Read-only aggregates for the dashboard and Transactions (Phase 6). Only ACTIVE revisions count; the
 * domain's `TransactionFilter.matches` and `DashboardCalculator` are the reference definitions.
 */
@Dao
interface ReportDao {

    /** [text] is in search-key form and LIKE-escaped with `\`; [digits] matches anywhere in the mobile number. */
    @Query(
        """
        SELECT e.*, c.name AS customer_name, c.mobile AS customer_mobile
        FROM ledger_entry e JOIN customer c ON c.id = e.customer_id
        WHERE e.status = 'ACTIVE' AND e.entry_type IN (:types)
          AND (:fromDay IS NULL OR e.entry_date >= :fromDay)
          AND (:toDay IS NULL OR e.entry_date <= :toDay)
          AND (:customerId IS NULL OR e.customer_id = :customerId)
          AND (:text = '' OR c.name_normalized LIKE '%' || :text || '%' ESCAPE '\'
               OR (:digits <> '' AND c.mobile LIKE '%' || :digits || '%'))
        """,
    )
    fun transactions(
        types: List<String>,
        fromDay: Long?,
        toDay: Long?,
        customerId: Long?,
        text: String,
        digits: String,
    ): Flow<List<TransactionRowEntity>>

    @Query("SELECT COUNT(*) AS total, COALESCE(SUM(is_archived = 0), 0) AS active FROM customer")
    fun customerCounts(): Flow<CustomerCountRow>

    /** SELL and BUY totals, all time when [day] is null, else for that business date. */
    @Query(
        """
        SELECT entry_type, COUNT(*) AS count, COALESCE(SUM(pieces), 0) AS eggs, COALESCE(SUM(amount_paise), 0) AS value
        FROM ledger_entry
        WHERE status = 'ACTIVE' AND entry_type IN ('SELL', 'BUY') AND (:day IS NULL OR entry_date = :day)
        GROUP BY entry_type
        """,
    )
    fun tradeTotals(day: Long?): Flow<List<TradeTotalRow>>

    /** Sums of positive and of negative customer balances, never netted across customers. */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN b > 0 THEN b ELSE 0 END), 0) AS receivable,
               COALESCE(SUM(CASE WHEN b < 0 THEN -b ELSE 0 END), 0) AS payable,
               COALESCE(SUM(b > 0), 0) AS receivable_count,
               COALESCE(SUM(b < 0), 0) AS payable_count
        FROM (SELECT SUM(balance_effect_paise) AS b FROM ledger_entry WHERE status = 'ACTIVE' GROUP BY customer_id)
        """,
    )
    fun dues(): Flow<DuesRow>

    /** The most recently recorded transactions (an edit counts as recorded when it was made). */
    @Query(
        """
        SELECT e.*, c.name AS customer_name, c.mobile AS customer_mobile
        FROM ledger_entry e JOIN customer c ON c.id = e.customer_id
        WHERE e.status = 'ACTIVE' AND e.entry_type IN ('SELL', 'BUY', 'PAYMENT_RECEIVED', 'PAYMENT_MADE')
        ORDER BY e.created_at DESC, e.id DESC
        LIMIT :limit
        """,
    )
    fun recent(limit: Int): Flow<List<TransactionRowEntity>>
}
