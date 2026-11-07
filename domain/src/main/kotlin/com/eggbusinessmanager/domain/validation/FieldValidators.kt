package com.eggbusinessmanager.domain.validation

import com.eggbusinessmanager.domain.error.ValidationCode

/**
 * Field rules shared by every form (spec § Validation Rules). Each `*Code` function returns null when
 * the value is valid, otherwise the reason it is not. All text is trimmed before checking.
 */
object FieldValidators {

    private val MOBILE = Regex("[6-9]\\d{9}")
    private val MOBILE_SEPARATORS = Regex("[\\s-]")
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")
    private val PIN_CODE = Regex("[1-9]\\d{5}")

    const val EMAIL_MAX = 254
    const val NAME_MIN = 2
    const val NAME_MAX = 80
    const val ADDRESS_MAX = 250
    const val CITY_MIN = 2
    const val CITY_MAX = 60

    private const val COUNTRY_CODE = "91"
    private const val MOBILE_DIGITS = 10

    /**
     * Normalizes an Indian mobile number to its 10 digits: removes spaces and dashes and an optional
     * `+91`, `91` or `0` prefix. Returns null when the result is not a valid mobile number.
     */
    fun normalizeMobile(raw: String): String? {
        var digits = raw.trim().replace(MOBILE_SEPARATORS, "")
        if (digits.startsWith("+")) digits = digits.removePrefix("+$COUNTRY_CODE").takeIf { it != digits } ?: return null
        if (digits.length == MOBILE_DIGITS + COUNTRY_CODE.length && digits.startsWith(COUNTRY_CODE)) {
            digits = digits.substring(COUNTRY_CODE.length)
        }
        if (digits.length == MOBILE_DIGITS + 1 && digits.startsWith("0")) digits = digits.substring(1)
        return digits.takeIf { MOBILE.matches(it) }
    }

    fun mobileCode(raw: String): ValidationCode? = when {
        raw.isBlank() -> ValidationCode.REQUIRED
        normalizeMobile(raw) == null -> ValidationCode.INVALID_FORMAT
        else -> null
    }

    /** Lower-cased, trimmed email, or null when left empty. */
    fun normalizeEmail(raw: String): String? = raw.trim().lowercase().ifEmpty { null }

    /** Email is optional: empty is valid. */
    fun optionalEmailCode(raw: String): ValidationCode? {
        val email = raw.trim()
        return when {
            email.isEmpty() -> null
            email.length > EMAIL_MAX -> ValidationCode.TOO_LONG
            !EMAIL.matches(email) -> ValidationCode.INVALID_FORMAT
            else -> null
        }
    }

    fun requiredTextCode(raw: String, min: Int, max: Int): ValidationCode? {
        val text = raw.trim()
        return when {
            text.isEmpty() -> ValidationCode.REQUIRED
            text.length < min -> ValidationCode.TOO_SHORT
            text.length > max -> ValidationCode.TOO_LONG
            else -> null
        }
    }

    fun optionalTextCode(raw: String, max: Int): ValidationCode? =
        if (raw.trim().length > max) ValidationCode.TOO_LONG else null

    /** Indian postal PIN code: 6 digits, the first 1–9. */
    fun pinCodeCode(raw: String): ValidationCode? {
        val pin = raw.trim()
        return when {
            pin.isEmpty() -> ValidationCode.REQUIRED
            !PIN_CODE.matches(pin) -> ValidationCode.INVALID_FORMAT
            else -> null
        }
    }
}
