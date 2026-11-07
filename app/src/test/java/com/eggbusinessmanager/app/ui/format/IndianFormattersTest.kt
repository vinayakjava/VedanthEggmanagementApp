package com.eggbusinessmanager.app.ui.format

import com.eggbusinessmanager.domain.money.Money
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class IndianFormattersTest {

    @Test
    fun `money uses lakh and crore grouping with 2 decimals`() {
        assertThat(IndianFormatters.money(Money.rupees(1_500))).isEqualTo("₹1,500.00")
        assertThat(IndianFormatters.money(Money.rupees(150_000, 5))).isEqualTo("₹1,50,000.05")
        assertThat(IndianFormatters.money(Money.rupees(12_345_678))).isEqualTo("₹1,23,45,678.00")
        assertThat(IndianFormatters.money(Money.MAX_ENTRY)).isEqualTo("₹99,99,99,999.99")
        assertThat(IndianFormatters.money(Money(7))).isEqualTo("₹0.07")
        assertThat(IndianFormatters.money(Money.ZERO)).isEqualTo("₹0.00")
    }

    @Test
    fun `negative amounts get a leading minus`() {
        assertThat(IndianFormatters.money(Money.rupees(-250))).isEqualTo("-₹250.00")
    }

    @Test
    fun `grouping edge cases`() {
        assertThat(IndianFormatters.group(0)).isEqualTo("0")
        assertThat(IndianFormatters.group(999)).isEqualTo("999")
        assertThat(IndianFormatters.group(1_000)).isEqualTo("1,000")
        assertThat(IndianFormatters.group(10_000)).isEqualTo("10,000")
        assertThat(IndianFormatters.group(100_000)).isEqualTo("1,00,000")
    }

    @Test
    fun `dates are day month year`() {
        assertThat(IndianFormatters.date(LocalDate.of(2026, 11, 8), Locale.ENGLISH)).isEqualTo("8 Nov 2026")
    }
}
