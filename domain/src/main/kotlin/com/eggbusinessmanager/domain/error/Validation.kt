package com.eggbusinessmanager.domain.error

/** Every input field that domain validation can reject. Stable names; :app maps each to a label. */
enum class Field {
    IDENTIFIER,
    OWNER_NAME,
    MOBILE,
    EMAIL,
    PASSWORD,
    CONFIRM_PASSWORD,
    CURRENT_PASSWORD,
    SHOP_NAME,
    ADDRESS,
    CITY,
    STATE,
    PIN_CODE,
    RECOVERY_PIN,
    CONFIRM_RECOVERY_PIN,
    SECURITY_QUESTIONS,
    SECURITY_ANSWER_1,
    SECURITY_ANSWER_2,
    SECURITY_ANSWER_3,
    CUSTOMER_NAME,
    NOTES,
    OPENING_AMOUNT,
    OPENING_DATE,
    ;

    companion object {
        /** The answer field for security question slot [index] (0-based). */
        fun securityAnswer(index: Int): Field = listOf(SECURITY_ANSWER_1, SECURITY_ANSWER_2, SECURITY_ANSWER_3)[index]
    }
}

/** Why a field was rejected. The pair (Field, ValidationCode) selects the message from the spec table. */
enum class ValidationCode {
    REQUIRED,
    TOO_SHORT,
    TOO_LONG,
    INVALID_FORMAT,
    MISMATCH,
    WEAK_PASSWORD,
    PASSWORD_EQUALS_MOBILE,
    WEAK_PIN,
    DUPLICATE_QUESTIONS,
    ANSWERS_ALL_SAME,
    ANSWER_EQUALS_PASSWORD,
    DATE_IN_FUTURE,
    DATE_TOO_EARLY,
}

/** Collects field errors, keeping only the first error per field, in the order fields were checked. */
class ValidationErrors {
    private val errors = LinkedHashMap<Field, ValidationCode>()

    fun add(field: Field, code: ValidationCode?) {
        if (code != null) errors.putIfAbsent(field, code)
    }

    val isEmpty: Boolean get() = errors.isEmpty()

    fun toMap(): Map<Field, ValidationCode> = errors.toMap()

    /** The validation failure to return, or null when everything is valid. */
    fun asFailure(): DomainResult.Err? = if (isEmpty) null else DomainResult.Err(DomainError.Validation(toMap()))

    /** `Ok(value())` when nothing failed, otherwise a [DomainError.Validation]. */
    inline fun <T> result(value: () -> T): DomainResult<T> =
        if (isEmpty) DomainResult.Ok(value()) else DomainResult.Err(DomainError.Validation(toMap()))
}
