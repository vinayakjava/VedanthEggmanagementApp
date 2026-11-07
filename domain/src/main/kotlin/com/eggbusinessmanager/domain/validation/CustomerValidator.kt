package com.eggbusinessmanager.domain.validation

import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.customer.OpeningBalance
import com.eggbusinessmanager.domain.customer.ValidCustomer
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.error.ValidationErrors
import com.eggbusinessmanager.domain.money.MoneyParser
import java.time.LocalDate

/**
 * Customer rules (spec § Validation Rules, FR-015): name 2–80, optional valid mobile, address ≤ 250,
 * notes ≤ 500, optional opening balance 0.00–99,99,99,999.99 with at most 2 decimals, dated on or
 * before today and not before 2000-01-01. Mobile uniqueness needs the database and is checked by
 * the use cases.
 */
object CustomerValidator {
    const val NOTES_MAX = 500
    val EARLIEST_DATE: LocalDate = LocalDate.of(2000, 1, 1)

    fun validate(draft: CustomerDraft, today: LocalDate): DomainResult<ValidCustomer> {
        val errors = ValidationErrors()
        errors.add(Field.CUSTOMER_NAME, FieldValidators.requiredTextCode(draft.name, FieldValidators.NAME_MIN, FieldValidators.NAME_MAX))
        if (draft.mobile.isNotBlank()) errors.add(Field.MOBILE, FieldValidators.mobileCode(draft.mobile))
        errors.add(Field.ADDRESS, FieldValidators.optionalTextCode(draft.address, FieldValidators.ADDRESS_MAX))
        errors.add(Field.NOTES, FieldValidators.optionalTextCode(draft.notes, NOTES_MAX))

        val amount = MoneyParser.parse(draft.openingAmount)
        errors.add(
            Field.OPENING_AMOUNT,
            when (amount) {
                MoneyParser.Result.Invalid -> ValidationCode.INVALID_FORMAT
                MoneyParser.Result.TooLarge -> ValidationCode.TOO_LONG
                else -> null
            },
        )
        val opening = (amount as? MoneyParser.Result.Valid)?.money
        if (opening != null) errors.add(Field.OPENING_DATE, dateCode(draft.openingDate, today))

        return errors.result {
            ValidCustomer(
                name = draft.name.trim(),
                mobile = draft.mobile.takeIf { it.isNotBlank() }?.let(FieldValidators::normalizeMobile),
                address = draft.address.trim().ifEmpty { null },
                notes = draft.notes.trim().ifEmpty { null },
                opening = opening?.let { OpeningBalance(it, draft.openingDirection, checkNotNull(draft.openingDate)) },
            )
        }
    }

    /** Business dates: required, not after today, not before 2000-01-01. */
    fun dateCode(date: LocalDate?, today: LocalDate): ValidationCode? = when {
        date == null -> ValidationCode.REQUIRED
        date.isAfter(today) -> ValidationCode.DATE_IN_FUTURE
        date.isBefore(EARLIEST_DATE) -> ValidationCode.DATE_TOO_EARLY
        else -> null
    }
}
