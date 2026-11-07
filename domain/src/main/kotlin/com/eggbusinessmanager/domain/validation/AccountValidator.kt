package com.eggbusinessmanager.domain.validation

import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.error.ValidationErrors
import com.eggbusinessmanager.domain.model.account.SecurityAnswerInput
import com.eggbusinessmanager.domain.model.account.SecurityQuestion

/**
 * Account rules (spec § Validation Rules, FR-002, FR-012). Used by the use cases and by the signup
 * screens for inline messages, so both always agree.
 */
object AccountValidator {

    const val PASSWORD_MIN = 8
    const val PASSWORD_MAX = 64
    const val RECOVERY_PIN_LENGTH = 6
    const val ANSWER_MIN = 2
    const val ANSWER_MAX = 50

    private val PIN_DIGITS = Regex("\\d{$RECOVERY_PIN_LENGTH}")

    /** Password: 8–64 characters, at least one letter and one digit, not the mobile number. */
    fun passwordCode(password: String, mobile: String?): ValidationCode? = when {
        password.isEmpty() -> ValidationCode.REQUIRED
        password.length !in PASSWORD_MIN..PASSWORD_MAX ||
            password.none { it.isLetter() } ||
            password.none { it.isDigit() } -> ValidationCode.WEAK_PASSWORD
        mobile != null && (password == mobile || FieldValidators.normalizeMobile(password) == mobile) ->
            ValidationCode.PASSWORD_EQUALS_MOBILE
        else -> null
    }

    fun confirmationCode(value: String, confirmation: String): ValidationCode? = when {
        confirmation.isEmpty() -> ValidationCode.REQUIRED
        value != confirmation -> ValidationCode.MISMATCH
        else -> null
    }

    /** Recovery PIN: exactly 6 digits, not all the same digit, not an ascending/descending run. */
    fun recoveryPinCode(pin: String): ValidationCode? = when {
        pin.isEmpty() -> ValidationCode.REQUIRED
        !PIN_DIGITS.matches(pin) -> ValidationCode.INVALID_FORMAT
        isEasyToGuess(pin) -> ValidationCode.WEAK_PIN
        else -> null
    }

    private fun isEasyToGuess(pin: String): Boolean {
        val steps = pin.zipWithNext { a, b -> b - a }.toSet()
        return steps == setOf(0) || steps == setOf(1) || steps == setOf(-1)
    }

    /** New password + confirmation (signup, change password, recovery). */
    fun newPassword(errors: ValidationErrors, password: String, confirmation: String, mobile: String?) {
        errors.add(Field.PASSWORD, passwordCode(password, mobile))
        errors.add(Field.CONFIRM_PASSWORD, confirmationCode(password, confirmation))
    }

    /** New recovery PIN + confirmation (signup, change PIN). */
    fun newRecoveryPin(errors: ValidationErrors, pin: String, confirmation: String) {
        errors.add(Field.RECOVERY_PIN, recoveryPinCode(pin))
        errors.add(Field.CONFIRM_RECOVERY_PIN, confirmationCode(pin, confirmation))
    }

    /**
     * Exactly 3 different questions, each answer 2–50 characters after normalizing, the answers not
     * all identical, and no answer equal to the password.
     */
    fun securityAnswers(errors: ValidationErrors, answers: List<SecurityAnswerInput>, password: String?) {
        val questions = answers.map { it.question }
        when {
            answers.size != SecurityQuestion.REQUIRED_COUNT || questions.any { it == null } ->
                errors.add(Field.SECURITY_QUESTIONS, ValidationCode.REQUIRED)
            questions.toSet().size != questions.size ->
                errors.add(Field.SECURITY_QUESTIONS, ValidationCode.DUPLICATE_QUESTIONS)
        }
        val normalizedPassword = password?.let(SecurityAnswerNormalizer::normalize)
        val normalized = answers.map { SecurityAnswerNormalizer.normalize(it.answer) }
        normalized.take(SecurityQuestion.REQUIRED_COUNT).forEachIndexed { index, answer ->
            val field = Field.securityAnswer(index)
            errors.add(field, answerCode(answer))
            if (normalizedPassword != null && answer == normalizedPassword) {
                errors.add(field, ValidationCode.ANSWER_EQUALS_PASSWORD)
            }
        }
        if (normalized.size == SecurityQuestion.REQUIRED_COUNT && normalized.toSet().size == 1 && normalized[0].isNotEmpty()) {
            errors.add(Field.securityAnswer(SecurityQuestion.REQUIRED_COUNT - 1), ValidationCode.ANSWERS_ALL_SAME)
        }
    }

    private fun answerCode(normalized: String): ValidationCode? = when {
        normalized.isEmpty() -> ValidationCode.REQUIRED
        normalized.length < ANSWER_MIN -> ValidationCode.TOO_SHORT
        normalized.length > ANSWER_MAX -> ValidationCode.TOO_LONG
        else -> null
    }
}
