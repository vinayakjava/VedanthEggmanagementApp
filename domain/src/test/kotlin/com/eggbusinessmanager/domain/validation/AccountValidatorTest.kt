package com.eggbusinessmanager.domain.validation

import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.error.ValidationErrors
import com.eggbusinessmanager.domain.model.account.IndianState
import com.eggbusinessmanager.domain.model.account.SecurityAnswerInput
import com.eggbusinessmanager.domain.model.account.SecurityQuestion
import com.eggbusinessmanager.domain.model.account.SignupForm
import com.eggbusinessmanager.domain.model.account.ValidSignup
import com.eggbusinessmanager.testing.SignupForms
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccountValidatorTest {

    @Test
    fun `password needs 8 to 64 chars with a letter and a digit`() {
        assertThat(AccountValidator.passwordCode("", null)).isEqualTo(ValidationCode.REQUIRED)
        listOf("abc123", "abcdefgh", "12345678", "a1" + "x".repeat(63)).forEach {
            assertThat(AccountValidator.passwordCode(it, null)).isEqualTo(ValidationCode.WEAK_PASSWORD)
        }
        assertThat(AccountValidator.passwordCode("eggs2024", null)).isNull()
        assertThat(AccountValidator.passwordCode("a1" + "x".repeat(62), null)).isNull()
    }

    @Test
    fun `password must not be the mobile number`() {
        assertThat(AccountValidator.passwordCode("9876543210", "9876543210")).isEqualTo(ValidationCode.WEAK_PASSWORD)
        assertThat(AccountValidator.passwordCode("+91a9876543210", "9876543210")).isNull()
        // A letter-containing password cannot be a mobile number; check the equality rule directly too.
        assertThat(AccountValidator.passwordCode("p9876543210", "p9876543210")).isEqualTo(ValidationCode.PASSWORD_EQUALS_MOBILE)
    }

    @Test
    fun `confirmation must match exactly`() {
        assertThat(AccountValidator.confirmationCode("abc", "")).isEqualTo(ValidationCode.REQUIRED)
        assertThat(AccountValidator.confirmationCode("abc", "abC")).isEqualTo(ValidationCode.MISMATCH)
        assertThat(AccountValidator.confirmationCode("abc", "abc")).isNull()
    }

    @Test
    fun `recovery PIN must be 6 digits and not easy to guess`() {
        assertThat(AccountValidator.recoveryPinCode("")).isEqualTo(ValidationCode.REQUIRED)
        listOf("12345", "1234567", "12a456", "12 456").forEach {
            assertThat(AccountValidator.recoveryPinCode(it)).isEqualTo(ValidationCode.INVALID_FORMAT)
        }
        listOf("000000", "777777", "123456", "456789", "987654", "654321").forEach {
            assertThat(AccountValidator.recoveryPinCode(it)).isEqualTo(ValidationCode.WEAK_PIN)
        }
        listOf("482916", "112233", "135790", "102030").forEach {
            assertThat(AccountValidator.recoveryPinCode(it)).isNull()
        }
    }

    @Test
    fun `security questions must be 3 different ones`() {
        val missing = errorsFor(listOf(SecurityAnswerInput(null, "aa"), answer(1), answer(2)))
        assertThat(missing).containsEntry(Field.SECURITY_QUESTIONS, ValidationCode.REQUIRED)

        val duplicate = errorsFor(listOf(answer(0), answer(0, "bb"), answer(2)))
        assertThat(duplicate).containsEntry(Field.SECURITY_QUESTIONS, ValidationCode.DUPLICATE_QUESTIONS)

        val tooFew = errorsFor(listOf(answer(0), answer(1)))
        assertThat(tooFew).containsEntry(Field.SECURITY_QUESTIONS, ValidationCode.REQUIRED)
    }

    @Test
    fun `answers are 2 to 50 chars after normalizing`() {
        val errors = errorsFor(listOf(answer(0, "  "), answer(1, " a "), answer(2, "x".repeat(51))))
        assertThat(errors).containsEntry(Field.SECURITY_ANSWER_1, ValidationCode.REQUIRED)
        assertThat(errors).containsEntry(Field.SECURITY_ANSWER_2, ValidationCode.TOO_SHORT)
        assertThat(errors).containsEntry(Field.SECURITY_ANSWER_3, ValidationCode.TOO_LONG)
    }

    @Test
    fun `answers must not all be identical and must not equal the password`() {
        val same = errorsFor(listOf(answer(0, "Pune"), answer(1, " pune "), answer(2, "PUNE")))
        assertThat(same).containsExactly(Field.SECURITY_ANSWER_3, ValidationCode.ANSWERS_ALL_SAME)

        val password = errorsFor(listOf(answer(0, "Eggs2024"), answer(1), answer(2)), password = "eggs2024")
        assertThat(password).containsExactly(Field.SECURITY_ANSWER_1, ValidationCode.ANSWER_EQUALS_PASSWORD)
    }

    @Test
    fun `a complete form validates and is normalized`() {
        val result = SignupValidator.signup(SignupForms.valid)

        val valid = (result as DomainResult.Ok<ValidSignup>).value
        assertThat(valid.mobile).isEqualTo("9876543210")
        assertThat(valid.email).isEqualTo("owner@example.com")
        assertThat(valid.profile.state).isEqualTo(IndianState.MAHARASHTRA)
        assertThat(valid.securityAnswers.map { it.second }).containsExactly("saraswati vidyalaya", "nashik", "poha").inOrder()
    }

    @Test
    fun `blank optional fields become null`() {
        val valid = (SignupValidator.signup(SignupForms.valid.copy(email = " ", address = "  ")) as DomainResult.Ok).value
        assertThat(valid.email).isNull()
        assertThat(valid.profile.address).isNull()
    }

    @Test
    fun `an empty form reports every required field`() {
        val result = SignupValidator.signup(SignupForm())

        val fields = ((result as DomainResult.Err).error as DomainError.Validation).fields
        assertThat(fields.keys).containsAtLeast(
            Field.OWNER_NAME, Field.MOBILE, Field.PASSWORD, Field.CONFIRM_PASSWORD, Field.SHOP_NAME,
            Field.CITY, Field.STATE, Field.PIN_CODE, Field.RECOVERY_PIN, Field.CONFIRM_RECOVERY_PIN,
            Field.SECURITY_QUESTIONS, Field.SECURITY_ANSWER_1,
        )
        assertThat(fields).doesNotContainKey(Field.EMAIL)
        assertThat(fields).doesNotContainKey(Field.ADDRESS)
    }

    @Test
    fun `each signup step only reports its own fields`() {
        val form = SignupForm()
        assertThat(SignupValidator.accountStep(form).toMap().keys)
            .containsExactly(Field.OWNER_NAME, Field.MOBILE, Field.PASSWORD, Field.CONFIRM_PASSWORD)
        assertThat(SignupValidator.shopStep(form).toMap().keys)
            .containsExactly(Field.SHOP_NAME, Field.CITY, Field.STATE, Field.PIN_CODE)
        assertThat(SignupValidator.securityStep(SignupForms.valid).isEmpty).isTrue()
    }

    @Test
    fun `mismatched recovery PIN confirmation is reported`() {
        val errors = SignupValidator.securityStep(SignupForms.valid.copy(confirmRecoveryPin = "482917")).toMap()
        assertThat(errors).containsExactly(Field.CONFIRM_RECOVERY_PIN, ValidationCode.MISMATCH)
    }

    private fun answer(index: Int, text: String = "answer$index") =
        SecurityAnswerInput(SecurityQuestion.entries[index], text)

    private fun errorsFor(answers: List<SecurityAnswerInput>, password: String? = null) =
        ValidationErrors().also { AccountValidator.securityAnswers(it, answers, password) }.toMap()
}
