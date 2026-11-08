package com.eggbusinessmanager.app.ui.error

import androidx.annotation.StringRes
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.model.StartupState

/**
 * The only place that turns domain errors into user-facing messages (contracts/domain-interfaces.md
 * § Error-to-message mapping). Messages never include technical detail.
 */
object ErrorMessages {

    fun forError(error: DomainError): UiText = when (error) {
        is DomainError.DuplicateMobile -> UiText.Resource(R.string.error_duplicate_mobile, listOf(error.customerName))
        else -> UiText.Resource(simpleMessage(error))
    }

    @Suppress("CyclomaticComplexMethod") // A flat lookup table; splitting it would hide the mapping.
    @StringRes
    private fun simpleMessage(error: DomainError): Int = when (error) {
        is DomainError.Validation -> R.string.error_validation
        DomainError.InvalidCredentials -> R.string.error_invalid_credentials
        DomainError.IncorrectPassword -> R.string.error_incorrect_password
        DomainError.IncorrectRecoveryPin -> R.string.error_incorrect_recovery_pin
        DomainError.IncorrectSecurityAnswers -> R.string.error_incorrect_answers
        is DomainError.Locked -> R.string.error_locked
        DomainError.AccountAlreadyExists -> R.string.error_account_exists
        DomainError.NoAccount -> R.string.error_no_account
        DomainError.NotFound -> R.string.error_not_found
        is DomainError.DuplicateMobile -> R.string.error_duplicate_mobile
        DomainError.CustomerHasEntries -> R.string.error_customer_has_entries
        DomainError.EntryNotEditable -> R.string.error_entry_not_editable
        DomainError.UnitLocked -> R.string.error_unit_locked
        DomainError.LastActiveVariety -> R.string.error_last_variety
        DomainError.LowStorage -> R.string.error_low_storage
        DomainError.StorageUnavailable -> R.string.error_storage_unavailable
        is DomainError.Unexpected -> R.string.error_generic
    }

    /** Inline message for one invalid field (spec § Validation Rules). */
    fun forField(field: Field, code: ValidationCode): UiText = UiText.Resource(fieldMessage(field, code))

    /** All field messages of a validation error, keyed by field. */
    fun forFields(fields: Map<Field, ValidationCode>): Map<Field, UiText> =
        fields.mapValues { (field, code) -> forField(field, code) }

    @Suppress("CyclomaticComplexMethod") // A flat lookup table; splitting it would hide the mapping.
    @StringRes
    private fun fieldMessage(field: Field, code: ValidationCode): Int = when (field) {
        Field.IDENTIFIER -> R.string.v_identifier_required
        Field.OWNER_NAME -> if (code == ValidationCode.REQUIRED) R.string.v_owner_name_required else R.string.v_name_length
        Field.SHOP_NAME -> if (code == ValidationCode.REQUIRED) R.string.v_shop_name_required else R.string.v_name_length
        Field.MOBILE -> R.string.v_mobile
        Field.EMAIL -> R.string.v_email
        Field.PASSWORD -> when (code) {
            ValidationCode.REQUIRED -> R.string.v_password_required
            ValidationCode.PASSWORD_EQUALS_MOBILE -> R.string.v_password_equals_mobile
            else -> R.string.v_password_rules
        }
        Field.CONFIRM_PASSWORD -> confirmation(code, R.string.v_passwords_mismatch)
        Field.CURRENT_PASSWORD -> R.string.v_current_password_required
        Field.ADDRESS -> R.string.v_address_too_long
        Field.CITY -> if (code == ValidationCode.REQUIRED) R.string.v_city_required else R.string.v_city_length
        Field.STATE -> R.string.v_state_required
        Field.PIN_CODE -> R.string.v_pin_code
        Field.RECOVERY_PIN -> when (code) {
            ValidationCode.REQUIRED -> R.string.v_recovery_pin_required
            ValidationCode.WEAK_PIN -> R.string.v_recovery_pin_weak
            else -> R.string.v_recovery_pin_format
        }
        Field.CONFIRM_RECOVERY_PIN -> confirmation(code, R.string.v_pins_mismatch)
        Field.SECURITY_QUESTIONS ->
            if (code == ValidationCode.DUPLICATE_QUESTIONS) R.string.v_questions_duplicate else R.string.v_questions_required
        Field.SECURITY_ANSWER_1, Field.SECURITY_ANSWER_2, Field.SECURITY_ANSWER_3 -> when (code) {
            ValidationCode.TOO_LONG -> R.string.v_answer_long
            ValidationCode.ANSWERS_ALL_SAME -> R.string.v_answers_all_same
            ValidationCode.ANSWER_EQUALS_PASSWORD -> R.string.v_answer_equals_password
            else -> R.string.v_answer_short
        }
        Field.CUSTOMER_NAME -> if (code == ValidationCode.REQUIRED) R.string.v_customer_name_required else R.string.v_name_length
        Field.NOTES -> R.string.v_notes_too_long
        Field.OPENING_AMOUNT -> R.string.v_amount
        Field.OPENING_DATE, Field.ENTRY_DATE ->
            if (code == ValidationCode.DATE_IN_FUTURE) R.string.v_date_future else R.string.v_date_invalid
        else -> tradeFieldMessage(field, code)
    }

    /** Sell/Buy and catalog fields (spec § Validation Rules). */
    @Suppress("CyclomaticComplexMethod") // A flat lookup table; splitting it would hide the mapping.
    @StringRes
    private fun tradeFieldMessage(field: Field, code: ValidationCode): Int = when (field) {
        Field.CUSTOMER -> R.string.v_customer_required
        Field.VARIETY -> R.string.v_variety_required
        Field.UNIT -> R.string.v_unit_required
        Field.QUANTITY -> when (code) {
            ValidationCode.INVALID_FORMAT -> R.string.v_quantity_format
            ValidationCode.NOT_WHOLE_PIECES -> R.string.v_quantity_whole
            ValidationCode.OUT_OF_RANGE -> R.string.v_quantity_too_large
            else -> R.string.v_quantity_positive
        }
        Field.PRICE -> when (code) {
            ValidationCode.INVALID_FORMAT -> R.string.v_price_format
            ValidationCode.OUT_OF_RANGE -> R.string.v_price_too_large
            else -> R.string.v_price_positive
        }
        Field.TOTAL -> R.string.v_total_range
        Field.REMARKS -> R.string.v_notes_too_long
        Field.REASON -> R.string.v_reason_too_long
        Field.UNIT_NAME -> if (code == ValidationCode.DUPLICATE) R.string.v_unit_name_duplicate else R.string.v_unit_name
        Field.UNIT_PIECES -> R.string.v_unit_pieces
        Field.VARIETY_NAME -> if (code == ValidationCode.DUPLICATE) R.string.v_variety_name_duplicate else R.string.v_variety_name
        else -> R.string.error_validation
    }

    @StringRes
    private fun confirmation(code: ValidationCode, @StringRes mismatch: Int): Int =
        if (code == ValidationCode.MISMATCH) mismatch else R.string.v_confirm_required

    fun forStartupFailure(reason: StartupState.Failed.Reason): UiText = UiText.Resource(
        when (reason) {
            StartupState.Failed.Reason.KEY_UNAVAILABLE -> R.string.startup_failed_key_body
            StartupState.Failed.Reason.LOW_STORAGE -> R.string.startup_failed_storage_body
            StartupState.Failed.Reason.UNKNOWN -> R.string.startup_failed_unknown_body
        },
    )
}
