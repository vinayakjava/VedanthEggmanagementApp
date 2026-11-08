package com.eggbusinessmanager.app.ui.format

import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.settings.AppPreferences
import com.eggbusinessmanager.domain.settings.DigitGrouping
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Indian number and date formatting (research R15): rupees with lakh/crore grouping
 * (₹1,50,000.00) and Western digits in both English and Hindi. Pure Kotlin so it behaves the same
 * on every device and in unit tests.
 */
object IndianFormatters {

    private const val LAST_GROUP = 3
    private const val OTHER_GROUPS = 2

    /**
     * The owner's display preferences (Phase 11). Set once storage is ready and again when they change;
     * the activity is recreated on a change so every screen picks them up.
     */
    @Volatile
    var preferences: AppPreferences = AppPreferences()

    /** "₹1,50,000.00" by default; negative amounts get a leading minus ("-₹250.00"). */
    fun money(amount: Money, preferences: AppPreferences = this.preferences): String {
        val abs = amount.abs().paise
        val rupees = abs / Money.PAISE_PER_RUPEE
        val paise = abs % Money.PAISE_PER_RUPEE
        val sign = if (amount.isNegative) "-" else ""
        return "$sign${preferences.currencySymbol.text}${group(rupees, preferences)}.${paise.toString().padStart(2, '0')}"
    }

    /** Indian grouping (12,34,56,789) by default, or international (123,456,789). */
    fun group(value: Long, preferences: AppPreferences = this.preferences): String = when (preferences.grouping) {
        DigitGrouping.INTERNATIONAL -> value.toString().reversed().chunked(LAST_GROUP).joinToString(",").reversed()
        DigitGrouping.INDIAN -> indianGroups(value.toString())
    }

    private fun indianGroups(digits: String): String {
        if (digits.length <= LAST_GROUP) return digits
        val head = digits.dropLast(LAST_GROUP)
        val firstLength = head.length % OTHER_GROUPS
        val headGroups = buildList {
            if (firstLength > 0) add(head.take(firstLength))
            addAll(head.drop(firstLength).chunked(OTHER_GROUPS))
        }
        return (headGroups + digits.takeLast(LAST_GROUP)).joinToString(",")
    }

    /** "8 Nov 2026" / "8 नव॰ 2026" by default, or the chosen numeric format. */
    fun date(date: LocalDate, locale: Locale): String = DateTimeFormatter.ofPattern(preferences.dateStyle.pattern, locale).format(date)
}
