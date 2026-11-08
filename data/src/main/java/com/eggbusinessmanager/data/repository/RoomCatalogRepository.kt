package com.eggbusinessmanager.data.repository

import com.eggbusinessmanager.data.db.EggBusinessDatabase
import com.eggbusinessmanager.data.db.entity.EggVarietyEntity
import com.eggbusinessmanager.data.db.entity.MeasurementUnitEntity
import com.eggbusinessmanager.data.di.IoDispatcher
import com.eggbusinessmanager.domain.catalog.EggVariety
import com.eggbusinessmanager.domain.catalog.MeasurementUnit
import com.eggbusinessmanager.domain.repository.CatalogRepository
import com.eggbusinessmanager.domain.validation.SearchKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider

/** Units and varieties in the encrypted database (FR-059, FR-027a). */
class RoomCatalogRepository @Inject constructor(
    private val database: Provider<EggBusinessDatabase>,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CatalogRepository {

    private val dao get() = database.get().catalogDao()

    override fun observeUnits(includeInactive: Boolean): Flow<List<MeasurementUnit>> =
        flow { emitAll(dao.observeUnits(includeInactive)) }.map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged().flowOn(io)

    override fun observeVarieties(includeInactive: Boolean): Flow<List<EggVariety>> =
        flow { emitAll(dao.observeVarieties(includeInactive)) }.map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged().flowOn(io)

    override suspend fun unit(id: Long): MeasurementUnit? = withContext(io) { dao.unit(id)?.toDomain() }

    override suspend fun variety(id: Long): EggVariety? = withContext(io) { dao.variety(id)?.toDomain() }

    override suspend fun unitNameExists(name: String, excludingId: Long?): Boolean =
        withContext(io) { dao.unitNameExists(SearchKey.of(name), excludingId) }

    override suspend fun varietyNameExists(name: String, excludingId: Long?): Boolean =
        withContext(io) { dao.varietyNameExists(SearchKey.of(name), excludingId) }

    override suspend fun insertUnit(name: String, piecesPerUnit: Int): Long = withContext(io) {
        dao.insertUnit(
            MeasurementUnitEntity(
                name = name,
                nameNormalized = SearchKey.of(name),
                piecesPerUnit = piecesPerUnit,
                sortOrder = dao.nextUnitOrder(),
            ),
        )
    }

    override suspend fun updateUnit(id: Long, name: String, piecesPerUnit: Int) = withContext(io) {
        dao.updateUnit(id, name, SearchKey.of(name), piecesPerUnit)
    }

    override suspend fun setUnitActive(id: Long, active: Boolean) = withContext(io) { dao.setUnitActive(id, active) }

    override suspend fun insertVariety(name: String): Long = withContext(io) {
        dao.insertVariety(EggVarietyEntity(name = name, nameNormalized = SearchKey.of(name), sortOrder = dao.nextVarietyOrder()))
    }

    override suspend fun renameVariety(id: Long, name: String) = withContext(io) { dao.renameVariety(id, name, SearchKey.of(name)) }

    override suspend fun setVarietyActive(id: Long, active: Boolean) = withContext(io) { dao.setVarietyActive(id, active) }

    override suspend fun setDefaultVariety(id: Long) = withContext(io) { dao.setDefaultVariety(id) }

    private fun MeasurementUnitEntity.toDomain() = MeasurementUnit(id, name, piecesPerUnit, isActive, isSystem)

    private fun EggVarietyEntity.toDomain() = EggVariety(id, name, isActive, isDefault)
}
