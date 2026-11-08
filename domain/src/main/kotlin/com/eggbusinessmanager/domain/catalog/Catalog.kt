package com.eggbusinessmanager.domain.catalog

import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.error.ValidationErrors

/**
 * A measurement unit with its own conversion to eggs (FR-059). Units are never assumed to share a
 * factor: Tray = 30 and Box = 210 by default, and the owner can change any of them except Piece.
 */
data class MeasurementUnit(
    val id: Long,
    val name: String,
    val piecesPerUnit: Int,
    val isActive: Boolean,
    /** Piece: the base unit, which can never be edited or deactivated. */
    val isSystem: Boolean,
)

/** An egg variety (FR-027/027a). Exactly one is the default for new entries. */
data class EggVariety(
    val id: Long,
    val name: String,
    val isActive: Boolean,
    val isDefault: Boolean,
)

/** Unit and variety rules (spec § Validation Rules). Name uniqueness is checked against the database. */
object CatalogValidator {
    const val UNIT_NAME_MAX = 20
    const val VARIETY_NAME_MAX = 30
    const val PIECES_MIN = 1
    const val PIECES_MAX = 10_000

    data class ValidUnit(val name: String, val piecesPerUnit: Int)

    fun unit(name: String, pieces: String): DomainResult<ValidUnit> {
        val errors = ValidationErrors()
        errors.add(Field.UNIT_NAME, nameCode(name, UNIT_NAME_MAX))
        val count = pieces.trim().toIntOrNull()
        errors.add(
            Field.UNIT_PIECES,
            when {
                pieces.isBlank() -> ValidationCode.REQUIRED
                count == null || count !in PIECES_MIN..PIECES_MAX -> ValidationCode.OUT_OF_RANGE
                else -> null
            },
        )
        return errors.result { ValidUnit(name.trim(), checkNotNull(count)) }
    }

    fun varietyName(name: String): DomainResult<String> {
        val errors = ValidationErrors()
        errors.add(Field.VARIETY_NAME, nameCode(name, VARIETY_NAME_MAX))
        return errors.result { name.trim() }
    }

    private fun nameCode(name: String, max: Int): ValidationCode? = when {
        name.isBlank() -> ValidationCode.REQUIRED
        name.trim().length > max -> ValidationCode.TOO_LONG
        else -> null
    }
}
