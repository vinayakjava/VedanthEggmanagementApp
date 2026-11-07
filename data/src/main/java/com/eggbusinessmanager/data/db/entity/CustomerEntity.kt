package com.eggbusinessmanager.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A customer (data-model.md § customer). The opening balance is not a column: it is an
 * OPENING_BALANCE row in `ledger_entry` (research R4).
 */
@Entity(
    tableName = "customer",
    indices = [
        Index(value = ["name_normalized"]),
        Index(value = ["mobile"], unique = true),
        Index(value = ["is_archived", "name_normalized"]),
    ],
)
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "name_normalized") val nameNormalized: String,
    @ColumnInfo(name = "mobile") val mobile: String?,
    @ColumnInfo(name = "address") val address: String?,
    @ColumnInfo(name = "notes") val notes: String?,
    @ColumnInfo(name = "is_archived", defaultValue = "0") val isArchived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
