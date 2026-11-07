package com.eggbusinessmanager.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Append-only audit trail (data-model.md § audit_log). Business fields only, never secrets. */
@Entity(
    tableName = "audit_log",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customer_id"],
            // SET NULL keeps unused customers deletable; entity_id still names the deleted customer.
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["entity_type", "entity_id", "at"]),
        Index(value = ["customer_id", "at"]),
    ],
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "customer_id") val customerId: Long?,
    @ColumnInfo(name = "action") val action: String,
    @ColumnInfo(name = "before_json") val beforeJson: String?,
    @ColumnInfo(name = "after_json") val afterJson: String?,
    @ColumnInfo(name = "reason") val reason: String?,
    @ColumnInfo(name = "at") val at: Long,
)

/** Per-type reference counters (research R6): `OB-000001`, `S-000001`, … never reused. */
@Entity(tableName = "reference_sequence")
data class ReferenceSequenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "entry_type") val entryType: String,
    @ColumnInfo(name = "next_value") val nextValue: Long,
)
