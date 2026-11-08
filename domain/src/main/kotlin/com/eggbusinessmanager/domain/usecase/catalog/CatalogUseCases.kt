package com.eggbusinessmanager.domain.usecase.catalog

import com.eggbusinessmanager.domain.catalog.CatalogValidator
import com.eggbusinessmanager.domain.catalog.EggVariety
import com.eggbusinessmanager.domain.catalog.MeasurementUnit
import com.eggbusinessmanager.domain.customer.AuditEvent
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.error.catchingResult
import com.eggbusinessmanager.domain.repository.AuditRepository
import com.eggbusinessmanager.domain.repository.CatalogRepository
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.TransactionRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ObserveUnitsUseCase @Inject constructor(private val catalog: CatalogRepository) {
    operator fun invoke(includeInactive: Boolean = false): Flow<List<MeasurementUnit>> = catalog.observeUnits(includeInactive)
}

class ObserveVarietiesUseCase @Inject constructor(private val catalog: CatalogRepository) {
    operator fun invoke(includeInactive: Boolean = false): Flow<List<EggVariety>> = catalog.observeVarieties(includeInactive)
}

/**
 * Adds a unit, or renames/resizes one (FR-059, US12-2/3). Each unit has its own conversion to
 * eggs. Resizing never changes past entries: they keep the size they were saved with (FR-022).
 * Piece is the base unit and is locked.
 */
class SaveUnitUseCase @Inject constructor(
    private val catalog: CatalogRepository,
    private val audit: AuditRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /** [id] null adds a new unit. Returns the unit id. */
    suspend operator fun invoke(id: Long?, name: String, pieces: String): DomainResult<Long> = catchingResult {
        val existing = id?.let { catalog.unit(it) ?: return DomainResult.Err(DomainError.NotFound) }
        if (existing?.isSystem == true) return DomainResult.Err(DomainError.UnitLocked)
        val valid = when (val result = CatalogValidator.unit(name, pieces)) {
            is DomainResult.Err -> return result
            is DomainResult.Ok -> result.value
        }
        if (catalog.unitNameExists(valid.name, id)) {
            return DomainResult.Err(DomainError.Validation(Field.UNIT_NAME, ValidationCode.DUPLICATE))
        }
        val now = clock.now()
        val unitId = transactions.inTransaction {
            if (existing == null) {
                catalog.insertUnit(valid.name, valid.piecesPerUnit).also {
                    audit.append(AuditEvent(AuditEvent.ENTITY_UNIT, it, null, AuditEvent.ACTION_CREATE, after = snapshot(valid), at = now))
                }
            } else {
                catalog.updateUnit(existing.id, valid.name, valid.piecesPerUnit)
                val action = if (existing.piecesPerUnit != valid.piecesPerUnit) AuditEvent.ACTION_UNIT_RESIZE else AuditEvent.ACTION_RENAME
                audit.append(
                    AuditEvent(
                        AuditEvent.ENTITY_UNIT, existing.id, null, action,
                        before = snapshot(CatalogValidator.ValidUnit(existing.name, existing.piecesPerUnit)),
                        after = snapshot(valid), at = now,
                    ),
                )
                existing.id
            }
        }
        DomainResult.Ok(unitId)
    }

    private fun snapshot(unit: CatalogValidator.ValidUnit) = mapOf("name" to unit.name, "pieces_per_unit" to unit.piecesPerUnit.toString())
}

/** Deactivate hides a unit from new entries; past entries keep showing it (US12-4). Piece is locked. */
class SetUnitActiveUseCase @Inject constructor(private val catalog: CatalogRepository) {
    suspend operator fun invoke(id: Long, active: Boolean): DomainResult<Unit> = catchingResult {
        val unit = catalog.unit(id) ?: return DomainResult.Err(DomainError.NotFound)
        if (unit.isSystem) return DomainResult.Err(DomainError.UnitLocked)
        catalog.setUnitActive(id, active)
        DomainResult.Ok(Unit)
    }
}

/** Adds or renames a variety (FR-027a); renaming changes the name everywhere it is shown. */
class SaveVarietyUseCase @Inject constructor(private val catalog: CatalogRepository) {
    suspend operator fun invoke(id: Long?, name: String): DomainResult<Long> = catchingResult {
        if (id != null && catalog.variety(id) == null) return DomainResult.Err(DomainError.NotFound)
        val valid = when (val result = CatalogValidator.varietyName(name)) {
            is DomainResult.Err -> return result
            is DomainResult.Ok -> result.value
        }
        if (catalog.varietyNameExists(valid, id)) {
            return DomainResult.Err(DomainError.Validation(Field.VARIETY_NAME, ValidationCode.DUPLICATE))
        }
        DomainResult.Ok(if (id == null) catalog.insertVariety(valid) else id.also { catalog.renameVariety(it, valid) })
    }
}

/** The default variety is pre-selected on the Buy and Sell screens. It must be active. */
class SetDefaultVarietyUseCase @Inject constructor(private val catalog: CatalogRepository) {
    suspend operator fun invoke(id: Long): DomainResult<Unit> = catchingResult {
        val variety = catalog.variety(id) ?: return DomainResult.Err(DomainError.NotFound)
        if (!variety.isActive) catalog.setVarietyActive(id, true)
        catalog.setDefaultVariety(id)
        DomainResult.Ok(Unit)
    }
}

/**
 * Deactivate or reactivate a variety. The last active one cannot be deactivated; deactivating the
 * default passes "default" to another active variety (FR-027a).
 */
class SetVarietyActiveUseCase @Inject constructor(
    private val catalog: CatalogRepository,
    private val transactions: TransactionRunner,
) {
    suspend operator fun invoke(id: Long, active: Boolean): DomainResult<Unit> = catchingResult {
        val variety = catalog.variety(id) ?: return DomainResult.Err(DomainError.NotFound)
        if (active) {
            catalog.setVarietyActive(id, true)
            return DomainResult.Ok(Unit)
        }
        val others = catalog.observeVarieties(includeInactive = false).first().filter { it.id != id }
        if (others.isEmpty()) return DomainResult.Err(DomainError.LastActiveVariety)
        transactions.inTransaction {
            catalog.setVarietyActive(id, false)
            if (variety.isDefault) catalog.setDefaultVariety(others.first().id)
        }
        DomainResult.Ok(Unit)
    }
}
