package com.eggbusinessmanager.domain.money

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TradeMathTest {

    private fun q(text: String) = (QuantityParser.parse(text) as QuantityParser.Result.Valid).quantity
    private fun rs(text: String) = (MoneyParser.parse(text) as MoneyParser.Result.Valid).money

    @Test
    fun `line total is exact when it fits in paise`() {
        assertThat(TradeMath.lineTotal(q("10"), rs("180"))).isEqualTo(Money.rupees(1_800))
        // Spec example: 7.5 × 183.50 = 1,376.25 exactly.
        assertThat(TradeMath.lineTotal(q("7.5"), rs("183.50"))).isEqualTo(Money.rupees(1_376, 25))
    }

    @Test
    fun `line total rounds half up to the paisa only when needed`() {
        // 1.005 × 1.00 = 1.005 → 1.01 (half up)
        assertThat(TradeMath.lineTotal(q("1.005"), rs("1"))).isEqualTo(Money(101))
        // 1.004 × 1.00 = 1.004 → 1.00
        assertThat(TradeMath.lineTotal(q("1.004"), rs("1"))).isEqualTo(Money(100))
        // 0.333 × 0.10 = 0.0333 → 0.03
        assertThat(TradeMath.lineTotal(q("0.333"), rs("0.10"))).isEqualTo(Money(3))
    }

    @Test
    fun `no floating point drift on repeated small amounts`() {
        val total = (1..1_000).fold(Money.ZERO) { sum, _ -> sum + TradeMath.lineTotal(q("0.1"), rs("0.10")) }
        assertThat(total).isEqualTo(Money.rupees(10))
    }

    @Test
    fun `largest allowed trade does not overflow`() {
        // 10,000,000 pieces as units of 1 at ₹10,00,000 per unit is computed exactly (then rejected by range checks).
        assertThat(TradeMath.lineTotal(q("10000000"), Money(100_000_000))).isEqualTo(Money(1_000_000_000_000_000L))
    }

    @Test
    fun `negative inputs are refused`() {
        assertThrows(IllegalArgumentException::class.java) { TradeMath.lineTotal(QuantityMilli(-1), Money(1)) }
    }

    @Test
    fun `pieces use each unit's own size and must be whole`() {
        assertThat(TradeMath.pieces(q("10"), 30)).isEqualTo(300L) // trays
        assertThat(TradeMath.pieces(q("2"), 210)).isEqualTo(420L) // boxes
        assertThat(TradeMath.pieces(q("1.5"), 12)).isEqualTo(18L) // dozens
        assertThat(TradeMath.pieces(q("0.5"), 30)).isEqualTo(15L)
        assertThat(TradeMath.pieces(q("0.05"), 30)).isNull() // 1.5 eggs
        assertThat(TradeMath.pieces(q("1.5"), 1)).isNull() // 1.5 pieces
    }

    @Test
    fun `quantities parse with up to 3 decimals`() {
        assertThat(q("7.5")).isEqualTo(QuantityMilli(7_500))
        assertThat(q("1,000")).isEqualTo(QuantityMilli(1_000_000))
        assertThat(q(".125")).isEqualTo(QuantityMilli(125))
        assertThat(QuantityParser.parse("")).isEqualTo(QuantityParser.Result.Blank)
        listOf("1.2345", "-1", "abc", ".", "1.2.3").forEach {
            assertThat(QuantityParser.parse(it)).isEqualTo(QuantityParser.Result.Invalid)
        }
    }

    @Test
    fun `quantity input text round-trips`() {
        listOf("10", "7.5", "0.125", "1.05").forEach { text ->
            assertThat(QuantityParser.toInput(q(text))).isEqualTo(text)
        }
    }
}
