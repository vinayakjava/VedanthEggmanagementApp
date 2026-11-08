package com.eggbusinessmanager.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The central financial table (data-model.md § ledger_entry): one row per revision, append-only.
 * Every column of the model is present now so the v1 schema does not change when trades and payments
 * arrive; the trade/payment columns stay null until then. Rules Room cannot express (sign of
 * `balance_effect_paise`, append-only updates, no deletes, one ACTIVE revision per root) are enforced
 * by triggers and a partial unique index created in [com.eggbusinessmanager.data.db.SchemaCallback].
 */
@Entity(
    tableName = "ledger_entry",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customer_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = EggVarietyEntity::class,
            parentColumns = ["id"],
            childColumns = ["variety_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MeasurementUnitEntity::class,
            parentColumns = ["id"],
            childColumns = ["unit_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["customer_id", "status", "entry_date", "created_at"]),
        Index(value = ["status", "entry_date", "entry_type"]),
        Index(value = ["root_entry_id"]),
        Index(value = ["reference_no"]),
        Index(value = ["linked_root_entry_id"]),
        Index(value = ["variety_id"]),
        Index(value = ["unit_id"]),
        Index(value = ["customer_id", "entry_type", "variety_id", "unit_id", "status", "entry_date"]),
        Index(value = ["client_request_id"], unique = true),
    ],
)
data class LedgerEntryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "root_entry_id") val rootEntryId: Long,
    @ColumnInfo(name = "supersedes_entry_id") val supersedesEntryId: Long? = null,
    @ColumnInfo(name = "replaced_by_root_id") val replacedByRootId: Long? = null,
    @ColumnInfo(name = "entry_type") val entryType: String,
    @ColumnInfo(name = "reference_no") val referenceNo: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "customer_id") val customerId: Long,
    @ColumnInfo(name = "entry_date") val entryDate: Long,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    @ColumnInfo(name = "balance_effect_paise") val balanceEffectPaise: Long,
    @ColumnInfo(name = "variety_id") val varietyId: Long? = null,
    @ColumnInfo(name = "unit_id") val unitId: Long? = null,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long? = null,
    @ColumnInfo(name = "pieces_per_unit_snapshot") val piecesPerUnitSnapshot: Int? = null,
    @ColumnInfo(name = "pieces") val pieces: Long? = null,
    @ColumnInfo(name = "price_per_unit_paise") val pricePerUnitPaise: Long? = null,
    @ColumnInfo(name = "payment_mode") val paymentMode: String? = null,
    @ColumnInfo(name = "payment_reference") val paymentReference: String? = null,
    @ColumnInfo(name = "opening_direction") val openingDirection: String? = null,
    @ColumnInfo(name = "linked_root_entry_id") val linkedRootEntryId: Long? = null,
    @ColumnInfo(name = "remarks") val remarks: String? = null,
    @ColumnInfo(name = "void_reason") val voidReason: String? = null,
    @ColumnInfo(name = "voided_at") val voidedAt: Long? = null,
    @ColumnInfo(name = "client_request_id") val clientRequestId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
