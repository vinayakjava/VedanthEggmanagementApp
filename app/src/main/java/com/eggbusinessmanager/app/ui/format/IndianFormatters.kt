package com.eggbusinessmanager.app.ui.format

import com.eggbusinessmanager.domain.money.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Indian number and date formatting (research R15): rupees with lakh/crore grouping
 * (₹1,50,000.00) and Western digits in both English and Hindi. Pure Kotlin so it behaves the same
 * on every device and in unit tests.
 */
object IndianFormatters {

    private const val RUPEE = "₹"
    private const val LAST_GROUP = 3
    private const val OTHER_GROUPS = 2

    /** "₹1,50,000.00"; negative amounts get a leading minus ("-₹250.00"). */
    fun money(amount: Money): String {
        val abs = amount.abs().paise
        val rupees = abs / Money.PAISE_PER_RUPEE
        val paise = abs % Money.PAISE_PER_RUPEE
        val sign = if (amount.isNegative) "-" else ""
        return "$sign$RUPEE${group(rupees)}.${paise.toString().padStart(2, '0')}"
    }

    /** Indian digit grouping: the last 3 digits, then groups of 2 (12,34,56,789). */
    fun group(value: Long): String {
        val digits = value.toString()
        if (digits.length <= LAST_GROUP) return digits
        val head = digits.dropLast(LAST_GROUP)
        val firstLength = head.length % OTHER_GROUPS
        val headGroups = buildList {
            if (firstLength > 0) add(head.take(firstLength))
            addAll(head.drop(firstLength).chunked(OTHER_GROUPS))
        }
        return (headGroups + digits.takeLast(LAST_GROUP)).joinToString(",")
    }

    /** "8 Nov 2026" / "8 नव॰ 2026" depending on [locale]. */
    fun date(date: LocalDate, locale: Locale): String = DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(date)
}
