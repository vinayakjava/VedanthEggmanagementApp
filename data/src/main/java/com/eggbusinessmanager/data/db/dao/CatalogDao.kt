package com.eggbusinessmanager.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.eggbusinessmanager.data.db.entity.EggVarietyEntity
import com.eggbusinessmanager.data.db.entity.MeasurementUnitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {

    @Query("SELECT * FROM measurement_unit WHERE :includeInactive OR is_active = 1 ORDER BY sort_order, id")
    fun observeUnits(includeInactive: Boolean): Flow<List<MeasurementUnitEntity>>

    @Query("SELECT * FROM measurement_unit WHERE id = :id")
    suspend fun unit(id: Long): MeasurementUnitEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM measurement_unit WHERE name_normalized = :key AND (:excludingId IS NULL OR id <> :excludingId))")
    suspend fun unitNameExists(key: String, excludingId: Long?): Boolean

    @Query("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM measurement_unit")
    suspend fun nextUnitOrder(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUnit(unit: MeasurementUnitEntity): Long

    /** Piece (is_system) can never change. */
    @Query(
        "UPDATE measurement_unit SET name = :name, name_normalized = :key, pieces_per_unit = :pieces " +
            "WHERE id = :id AND is_system = 0",
    )
    suspend fun updateUnit(id: Long, name: String, key: String, pieces: Int)

    @Query("UPDATE measurement_unit SET is_active = :active WHERE id = :id AND is_system = 0")
    suspend fun setUnitActive(id: Long, active: Boolean)

    @Query("SELECT * FROM egg_variety WHERE :includeInactive OR is_active = 1 ORDER BY sort_order, id")
    fun observeVarieties(includeInactive: Boolean): Flow<List<EggVarietyEntity>>

    @Query("SELECT * FROM egg_variety WHERE id = :id")
    suspend fun variety(id: Long): EggVarietyEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM egg_variety WHERE name_normalized = :key AND (:excludingId IS NULL OR id <> :excludingId))")
    suspend fun varietyNameExists(key: String, excludingId: Long?): Boolean

    @Query("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM egg_variety")
    suspend fun nextVarietyOrder(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVariety(variety: EggVarietyEntity): Long

    @Query("UPDATE egg_variety SET name = :name, name_normalized = :key WHERE id = :id")
    suspend fun renameVariety(id: Long, name: String, key: String)

    @Query("UPDATE egg_variety SET is_active = :active WHERE id = :id")
    suspend fun setVarietyActive(id: Long, active: Boolean)

    /** Exactly one default: set this one and clear every other, in one statement. */
    @Query("UPDATE egg_variety SET is_default = (id = :id)")
    suspend fun setDefaultVariety(id: Long)
}
