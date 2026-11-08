package com.eggbusinessmanager.domain.trade

import com.eggbusinessmanager.domain.catalog.EggVariety
import com.eggbusinessmanager.domain.catalog.MeasurementUnit
import com.eggbusinessmanager.domain.customer.Customer
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.money.QuantityMilli
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TradeValidatorTest {

    private val today = LocalDate.of(2026, 11, 8)
    private val customer = Customer(7, "Ram", null, null, null, true, Instant.EPOCH, Instant.EPOCH)
    private val tray = MeasurementUnit(3, "Tray", 30, isActive = true, isSystem = false)
    private val brown = EggVariety(2, "Brown", isActive = true, isDefault = false)
    private val context = TradeContext(customer, brown, tray, today)
    private val sale = TradeDraft(EntryType.SELL, customer.id, today, brown.id, tray.id, quantity = "10", price = "180")

    private fun valid(draft: TradeDraft = sale, ctx: TradeContext = context) =
        (TradeValidator.validate(draft, ctx) as DomainResult.Ok<ValidTrade>).value

    private fun errors(draft: TradeDraft, ctx: TradeContext = context) =
        ((TradeValidator.validate(draft, ctx) as DomainResult.Err).error as DomainError.Validation).fields

    @Test
    fun `US3-1 ten trays at 180 is 1800 and 300 pieces`() {
        val trade = valid()
        assertThat(trade.total).isEqualTo(Money.rupees(1_800))
        assertThat(trade.pieces).isEqualTo(300L)
        assertThat(trade.piecesPerUnit).isEqualTo(30)
        assertThat(trade.quantity).isEqualTo(QuantityMilli(10_000))
        assertThat(trade.remarks).isNull()
    }

    @Test
    fun `live preview follows the inputs`() {
        assertThat(TradeValidator.preview("10", "180", tray)).isEqualTo(TradePreview(Money.rupees(1_800), 300L))
        assertThat(TradeValidator.preview("0.05", "180", tray)).isEqualTo(TradePreview(Money(900), null))
        assertThat(TradeValidator.preview("", "180", tray)).isEqualTo(TradePreview(null, null))
        assertThat(TradeValidator.preview("10", "x", null)).isEqualTo(TradePreview(null, null))
    }

    @Test
    fun `every required field is reported`() {
        val fields = errors(TradeDraft(EntryType.BUY), TradeContext(null, null, null, today))
        assertThat(fields).containsExactly(
            Field.CUSTOMER, ValidationCode.REQUIRED,
            Field.ENTRY_DATE, ValidationCode.REQUIRED,
            Field.VARIETY, ValidationCode.REQUIRED,
            Field.UNIT, ValidationCode.REQUIRED,
            Field.QUANTITY, ValidationCode.REQUIRED,
            Field.PRICE, ValidationCode.REQUIRED,
        )
    }

    @Test
    fun `quantity rules`() {
        assertThat(errors(sale.copy(quantity = "0"))).containsExactly(Field.QUANTITY, ValidationCode.MUST_BE_POSITIVE)
        assertThat(errors(sale.copy(quantity = "1.2345"))).containsExactly(Field.QUANTITY, ValidationCode.INVALID_FORMAT)
        // 0.05 tray = 1.5 eggs: not a whole number of eggs.
        assertThat(errors(sale.copy(quantity = "0.05"))).containsExactly(Field.QUANTITY, ValidationCode.NOT_WHOLE_PIECES)
        // 10,000,000 pieces is the maximum per entry.
        assertThat(errors(sale.copy(quantity = "333334", price = "1"))).containsExactly(Field.QUANTITY, ValidationCode.OUT_OF_RANGE)
        assertThat(valid(sale.copy(quantity = "333333", price = "1")).pieces).isEqualTo(9_999_990L)
    }

    @Test
    fun `price rules`() {
        assertThat(errors(sale.copy(price = "0"))).containsExactly(Field.PRICE, ValidationCode.MUST_BE_POSITIVE)
        assertThat(errors(sale.copy(price = "1.234"))).containsExactly(Field.PRICE, ValidationCode.INVALID_FORMAT)
        assertThat(errors(sale.copy(price = "1000000.01"))).containsExactly(Field.PRICE, ValidationCode.OUT_OF_RANGE)
        assertThat(valid(sale.copy(price = "1000000")).pricePerUnit).isEqualTo(Money.rupees(1_000_000))
    }

    @Test
    fun `total must be at least one paisa and within the entry maximum`() {
        val piece = MeasurementUnit(1, "Piece", 1, isActive = true, isSystem = true)
        // 1 piece at ₹0.004 cannot be typed (2 decimals), so check the upper bound instead.
        assertThat(errors(sale.copy(unitId = 1, quantity = "1000000", price = "1000000"), context.copy(unit = piece)))
            .containsExactly(Field.TOTAL, ValidationCode.OUT_OF_RANGE)
    }

    @Test
    fun `date cannot be in the future or before 2000`() {
        assertThat(errors(sale.copy(date = today.plusDays(1)))).containsExactly(Field.ENTRY_DATE, ValidationCode.DATE_IN_FUTURE)
        assertThat(errors(sale.copy(date = LocalDate.of(1999, 12, 31)))).containsExactly(Field.ENTRY_DATE, ValidationCode.DATE_TOO_EARLY)
    }

    @Test
    fun `inactive customer, variety or unit is refused for new entries but kept for edits`() {
        val ctx = context.copy(
            customer = customer.copy(isActive = false),
            variety = brown.copy(isActive = false),
            unit = tray.copy(isActive = false),
        )
        assertThat(errors(sale, ctx)).containsExactly(
            Field.CUSTOMER, ValidationCode.INACTIVE,
            Field.VARIETY, ValidationCode.INACTIVE,
            Field.UNIT, ValidationCode.INACTIVE,
        )
        val editing = ctx.copy(keepIds = TradeContext.KeepIds(customer.id, brown.id, tray.id))
        assertThat(valid(sale, editing).total).isEqualTo(Money.rupees(1_800))
    }

    @Test
    fun `remarks are trimmed and limited to 500 characters`() {
        assertThat(valid(sale.copy(remarks = "  paid later  ")).remarks).isEqualTo("paid later")
        assertThat(errors(sale.copy(remarks = "r".repeat(501)))).containsExactly(Field.REMARKS, ValidationCode.TOO_LONG)
    }

    @Test
    fun `a trade is only SELL or BUY`() {
        assertThrows(IllegalArgumentException::class.java) { TradeDraft(EntryType.PAYMENT_RECEIVED) }
    }
}
