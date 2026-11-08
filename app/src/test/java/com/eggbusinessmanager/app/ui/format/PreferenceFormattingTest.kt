package com.eggbusinessmanager.app.ui.format

import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.settings.AppPreferences
import com.eggbusinessmanager.domain.settings.CurrencySymbol
import com.eggbusinessmanager.domain.settings.DateStyle
import com.eggbusinessmanager.domain.settings.DigitGrouping
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** Currency and date display preferences (Phase 11). */
class PreferenceFormattingTest {

    @After
    fun reset() {
        IndianFormatters.preferences = AppPreferences()
    }

    private val lakh = Money.rupees(1_23_45_678, 90)
    private val date = LocalDate.of(2026, 11, 8)

    @Test
    fun `defaults are the rupee sign with Indian grouping and day-month-name dates`() {
        assertThat(IndianFormatters.money(lakh)).isEqualTo("₹1,23,45,678.90")
        assertThat(IndianFormatters.date(date, Locale.ENGLISH)).isEqualTo("8 Nov 2026")
    }

    @Test
    fun `Rs and international grouping`() {
        IndianFormatters.preferences = AppPreferences(currencySymbol = CurrencySymbol.RS, grouping = DigitGrouping.INTERNATIONAL)
        assertThat(IndianFormatters.money(lakh)).isEqualTo("Rs.12,345,678.90")
        assertThat(IndianFormatters.money(-Money.rupees(999))).isEqualTo("-Rs.999.00")
        assertThat(IndianFormatters.money(Money.rupees(1_000))).isEqualTo("Rs.1,000.00")
        assertThat(IndianFormatters.group(1_000_000)).isEqualTo("1,000,000")
        assertThat(IndianFormatters.group(999)).isEqualTo("999")
    }

    @Test
    fun `every date style`() {
        val expected = mapOf(
            DateStyle.DAY_MONTH_NAME to "8 Nov 2026",
            DateStyle.DD_MM_YYYY_SLASH to "08/11/2026",
            DateStyle.DD_MM_YYYY_DASH to "08-11-2026",
            DateStyle.ISO to "2026-11-08",
        )
        expected.forEach { (style, text) ->
            IndianFormatters.preferences = AppPreferences(dateStyle = style)
            assertThat(IndianFormatters.date(date, Locale.ENGLISH)).isEqualTo(text)
        }
    }
}
