package com.eggbusinessmanager.domain.repository

import com.eggbusinessmanager.domain.catalog.EggVariety
import com.eggbusinessmanager.domain.catalog.MeasurementUnit
import kotlinx.coroutines.flow.Flow

/** Measurement units and egg varieties (contracts/domain-interfaces.md § CatalogRepository). */
interface CatalogRepository {
    fun observeUnits(includeInactive: Boolean): Flow<List<MeasurementUnit>>

    fun observeVarieties(includeInactive: Boolean): Flow<List<EggVariety>>

    suspend fun unit(id: Long): MeasurementUnit?

    suspend fun variety(id: Long): EggVariety?

    suspend fun unitNameExists(name: String, excludingId: Long?): Boolean

    suspend fun varietyNameExists(name: String, excludingId: Long?): Boolean

    suspend fun insertUnit(name: String, piecesPerUnit: Int): Long

    /** Renames and/or resizes. Entries keep the size they were saved with (FR-022). */
    suspend fun updateUnit(id: Long, name: String, piecesPerUnit: Int)

    suspend fun setUnitActive(id: Long, active: Boolean)

    suspend fun insertVariety(name: String): Long

    suspend fun renameVariety(id: Long, name: String)

    suspend fun setVarietyActive(id: Long, active: Boolean)

    /** Makes [id] the only default variety. */
    suspend fun setDefaultVariety(id: Long)
}
