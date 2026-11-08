package com.eggbusinessmanager.domain.usecase.catalog

import com.eggbusinessmanager.domain.customer.AuditEvent
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.testing.FakeAuditRepository
import com.eggbusinessmanager.testing.FakeCatalogRepository
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.BOX
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.BROWN
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.COUNTRY
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.PIECE
import com.eggbusinessmanager.testing.FakeCatalogRepository.Companion.WHITE
import com.eggbusinessmanager.testing.FixedClock
import com.eggbusinessmanager.testing.ImmediateTransactionRunner
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** FR-059 units with configurable conversions; FR-027a varieties. */
class CatalogUseCasesTest {

    private val catalog = FakeCatalogRepository()
    private val audit = FakeAuditRepository()
    private val saveUnit = SaveUnitUseCase(catalog, audit, ImmediateTransactionRunner, FixedClock())
    private val unitActive = SetUnitActiveUseCase(catalog)
    private val units = ObserveUnitsUseCase(catalog)
    private val saveVariety = SaveVarietyUseCase(catalog)
    private val setDefault = SetDefaultVarietyUseCase(catalog)
    private val varietyActive = SetVarietyActiveUseCase(catalog, ImmediateTransactionRunner)
    private val varieties = ObserveVarietiesUseCase(catalog)

    private fun invalid(field: Field, code: ValidationCode) = DomainResult.Err(DomainError.Validation(field, code))

    @Test
    fun `defaults are Piece 1, Dozen 12, Tray 30, Box 210`() = runTest {
        assertThat(units().first().map { it.name to it.piecesPerUnit })
            .containsExactly("Piece" to 1, "Dozen" to 12, "Tray" to 30, "Box" to 210).inOrder()
    }

    @Test
    fun `a new unit gets its own conversion`() = runTest {
        val id = (saveUnit(null, " Crate ", "180") as DomainResult.Ok).value
        assertThat(catalog.unit(id)?.name).isEqualTo("Crate")
        assertThat(catalog.unit(id)?.piecesPerUnit).isEqualTo(180)
        assertThat(audit.events.single().action).isEqualTo(AuditEvent.ACTION_CREATE)
    }

    @Test
    fun `resizing and renaming are audited separately`() = runTest {
        saveUnit(BOX, "Box", "180")
        saveUnit(BOX, "Big box", "180")
        assertThat(audit.events.map { it.action }).containsExactly(AuditEvent.ACTION_UNIT_RESIZE, AuditEvent.ACTION_RENAME).inOrder()
        assertThat(audit.events.first().before?.get("pieces_per_unit")).isEqualTo("210")
    }

    @Test
    fun `unit names are unique and sizes are whole numbers from 1 to 10,000`() = runTest {
        assertThat(saveUnit(null, "tray", "25")).isEqualTo(invalid(Field.UNIT_NAME, ValidationCode.DUPLICATE))
        listOf("0", "10001", "2.5", "abc").forEach {
            assertThat(saveUnit(null, "Crate", it)).isEqualTo(invalid(Field.UNIT_PIECES, ValidationCode.OUT_OF_RANGE))
        }
        assertThat(saveUnit(null, "", "")).isEqualTo(
            DomainResult.Err(
                DomainError.Validation(mapOf(Field.UNIT_NAME to ValidationCode.REQUIRED, Field.UNIT_PIECES to ValidationCode.REQUIRED)),
            ),
        )
        assertThat(saveUnit(null, "x".repeat(21), "5")).isEqualTo(invalid(Field.UNIT_NAME, ValidationCode.TOO_LONG))
    }

    @Test
    fun `Piece is locked`() = runTest {
        assertThat(saveUnit(PIECE, "Egg", "1")).isEqualTo(DomainResult.Err(DomainError.UnitLocked))
        assertThat(unitActive(PIECE, false)).isEqualTo(DomainResult.Err(DomainError.UnitLocked))
    }

    @Test
    fun `deactivated units are hidden from new entries but still exist`() = runTest {
        unitActive(BOX, false)
        assertThat(units().first().map { it.name }).doesNotContain("Box")
        assertThat(units(includeInactive = true).first().map { it.name }).contains("Box")
        unitActive(BOX, true)
        assertThat(units().first().map { it.name }).contains("Box")
    }

    @Test
    fun `missing units and varieties are reported`() = runTest {
        assertThat(saveUnit(99, "X", "1")).isEqualTo(DomainResult.Err(DomainError.NotFound))
        assertThat(unitActive(99, false)).isEqualTo(DomainResult.Err(DomainError.NotFound))
        assertThat(saveVariety(99, "X")).isEqualTo(DomainResult.Err(DomainError.NotFound))
        assertThat(setDefault(99)).isEqualTo(DomainResult.Err(DomainError.NotFound))
        assertThat(varietyActive(99, false)).isEqualTo(DomainResult.Err(DomainError.NotFound))
    }

    @Test
    fun `varieties default to White, Brown and Country with White as default`() = runTest {
        assertThat(varieties().first().map { it.name }).containsExactly("White", "Brown", "Country (Desi)").inOrder()
        assertThat(varieties().first().single { it.isDefault }.id).isEqualTo(WHITE)
    }

    @Test
    fun `varieties can be added, renamed and made default`() = runTest {
        val duck = (saveVariety(null, "Duck") as DomainResult.Ok).value
        saveVariety(BROWN, "Brown (large)")
        setDefault(duck)

        val list = varieties().first()
        assertThat(list.map { it.name }).contains("Brown (large)")
        assertThat(list.single { it.isDefault }.id).isEqualTo(duck)
        assertThat(saveVariety(null, "white")).isEqualTo(invalid(Field.VARIETY_NAME, ValidationCode.DUPLICATE))
        assertThat(saveVariety(null, " ")).isEqualTo(DomainResult.Err(DomainError.Validation(Field.VARIETY_NAME, ValidationCode.REQUIRED)))
    }

    @Test
    fun `the last active variety cannot be deactivated and default moves on`() = runTest {
        varietyActive(WHITE, false)
        assertThat(varieties().first().single { it.isDefault }.id).isEqualTo(BROWN)
        varietyActive(BROWN, false)
        assertThat(varietyActive(COUNTRY, false)).isEqualTo(DomainResult.Err(DomainError.LastActiveVariety))

        varietyActive(WHITE, true)
        assertThat(varieties().first().map { it.id }).containsExactly(WHITE, COUNTRY)
    }

    @Test
    fun `making an inactive variety the default reactivates it`() = runTest {
        varietyActive(BROWN, false)
        setDefault(BROWN)
        assertThat(catalog.variety(BROWN)?.isActive).isTrue()
        assertThat(catalog.variety(BROWN)?.isDefault).isTrue()
    }
}
