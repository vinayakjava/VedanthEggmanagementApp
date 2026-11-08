package com.eggbusinessmanager.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A measurement unit and its own conversion to eggs (data-model.md § measurement_unit, FR-059).
 * Seeded with Piece (1, system), Dozen (12), Tray (30) and Box (210) by SchemaCallback.
 */
@Entity(
    tableName = "measurement_unit",
    indices = [Index(value = ["name_normalized"], unique = true)],
)
data class MeasurementUnitEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "name_normalized") val nameNormalized: String,
    @ColumnInfo(name = "pieces_per_unit") val piecesPerUnit: Int,
    @ColumnInfo(name = "is_active", defaultValue = "1") val isActive: Boolean = true,
    @ColumnInfo(name = "is_system", defaultValue = "0") val isSystem: Boolean = false,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
)

/** An egg variety (data-model.md § egg_variety, FR-027a). Seeded with White (default), Brown, Country (Desi). */
@Entity(
    tableName = "egg_variety",
    indices = [Index(value = ["name_normalized"], unique = true)],
)
data class EggVarietyEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "name_normalized") val nameNormalized: String,
    @ColumnInfo(name = "is_active", defaultValue = "1") val isActive: Boolean = true,
    @ColumnInfo(name = "is_default", defaultValue = "0") val isDefault: Boolean = false,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
)
