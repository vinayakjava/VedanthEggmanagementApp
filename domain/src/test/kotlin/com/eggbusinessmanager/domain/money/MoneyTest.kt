package com.eggbusinessmanager.domain.money

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {

    @Test
    fun `arithmetic is exact in paise`() {
        // 0.1 + 0.2 style errors are impossible: amounts are whole paise.
        assertThat(Money(10) + Money(20)).isEqualTo(Money(30))
        assertThat(Money.rupees(1500) - Money.rupees(1500, 1)).isEqualTo(Money(-1))
        assertThat(listOf(Money(1), Money(2), Money(3)).sum()).isEqualTo(Money(6))
        assertThat(Money(-250).abs()).isEqualTo(Money(250))
    }

    @Test
    fun `overflow throws instead of wrapping`() {
        assertThrows(ArithmeticException::class.java) { Money(Long.MAX_VALUE) + Money(1) }
        assertThrows(ArithmeticException::class.java) { -Money(Long.MIN_VALUE) }
    }

    @Test
    fun `sign helpers and ordering`() {
        assertThat(Money.ZERO.isZero).isTrue()
        assertThat(Money(5).isPositive).isTrue()
        assertThat(Money(-5).isNegative).isTrue()
        assertThat(Money(5)).isGreaterThan(Money(-5))
    }

    @Test
    fun `parses typed amounts with Indian grouping and at most 2 decimals`() {
        mapOf(
            "1500" to Money.rupees(1500),
            "1,500.5" to Money.rupees(1500, 50),
            " ₹ 1,50,000.05 " to Money.rupees(150_000, 5),
            ".75" to Money(75),
            "12." to Money.rupees(12),
            "0" to Money.ZERO,
            "999999999.99" to Money.MAX_ENTRY,
        ).forEach { (text, money) -> assertThat(MoneyParser.parse(text)).isEqualTo(MoneyParser.Result.Valid(money)) }
    }

    @Test
    fun `rejects blank, malformed and too-large amounts`() {
        assertThat(MoneyParser.parse("  ")).isEqualTo(MoneyParser.Result.Blank)
        listOf("1.234", "-5", "abc", "1.2.3", "1e3", ".").forEach {
            assertThat(MoneyParser.parse(it)).isEqualTo(MoneyParser.Result.Invalid)
        }
        assertThat(MoneyParser.parse("1000000000")).isEqualTo(MoneyParser.Result.TooLarge)
    }

    @Test
    fun `input text round-trips`() {
        listOf(Money.rupees(1500), Money.rupees(1500, 50), Money(5), Money.ZERO).forEach { money ->
            assertThat(MoneyParser.parse(MoneyParser.toInput(money))).isEqualTo(MoneyParser.Result.Valid(money))
        }
        assertThat(MoneyParser.toInput(Money.rupees(1500, 50))).isEqualTo("1500.50")
    }
}
