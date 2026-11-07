package com.eggbusinessmanager.app.ui.error

import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.model.StartupState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class ErrorMessagesTest {

    @Test
    fun `unexpected errors show the generic safe-data message, never technical detail`() {
        val text = ErrorMessages.forError(DomainError.Unexpected(RuntimeException("SQL detail")))
        assertThat(text).isEqualTo(UiText.Resource(R.string.error_generic))
    }

    @Test
    fun `every startup failure reason has its own message`() {
        val messages = StartupState.Failed.Reason.entries.map { ErrorMessages.forStartupFailure(it) }
        assertThat(messages.toSet()).hasSize(StartupState.Failed.Reason.entries.size)
    }

    @Test
    fun `sign-in failure uses the single generic message from the spec`() {
        assertThat(ErrorMessages.forError(DomainError.InvalidCredentials)).isEqualTo(UiText.Resource(R.string.error_invalid_credentials))
    }

    @Test
    fun `each auth failure has a distinct message`() {
        val errors = listOf(
            DomainError.InvalidCredentials,
            DomainError.IncorrectPassword,
            DomainError.IncorrectRecoveryPin,
            DomainError.IncorrectSecurityAnswers,
            DomainError.Locked(Instant.EPOCH),
            DomainError.AccountAlreadyExists,
            DomainError.NoAccount,
        )
        assertThat(errors.map(ErrorMessages::forError).toSet()).hasSize(errors.size)
    }

    @Test
    fun `every field and code combination maps to a message`() {
        Field.entries.forEach { field ->
            ValidationCode.entries.forEach { code ->
                assertThat((ErrorMessages.forField(field, code) as UiText.Resource).id).isNotEqualTo(0)
            }
        }
    }

    @Test
    fun `spec messages are used for the key password, PIN and question rules`() {
        assertThat(ErrorMessages.forField(Field.PASSWORD, ValidationCode.WEAK_PASSWORD))
            .isEqualTo(UiText.Resource(R.string.v_password_rules))
        assertThat(ErrorMessages.forField(Field.CONFIRM_PASSWORD, ValidationCode.MISMATCH))
            .isEqualTo(UiText.Resource(R.string.v_passwords_mismatch))
        assertThat(ErrorMessages.forField(Field.RECOVERY_PIN, ValidationCode.WEAK_PIN))
            .isEqualTo(UiText.Resource(R.string.v_recovery_pin_weak))
        assertThat(ErrorMessages.forField(Field.SECURITY_QUESTIONS, ValidationCode.DUPLICATE_QUESTIONS))
            .isEqualTo(UiText.Resource(R.string.v_questions_duplicate))
        assertThat(ErrorMessages.forField(Field.PIN_CODE, ValidationCode.INVALID_FORMAT)).isEqualTo(UiText.Resource(R.string.v_pin_code))
    }

    @Test
    fun `form feedback separates field errors, messages and lockouts`() {
        val validation = FormFeedback.from(DomainError.Validation(Field.CITY, ValidationCode.REQUIRED))
        assertThat(validation.error(Field.CITY)).isEqualTo(UiText.Resource(R.string.v_city_required))
        assertThat(validation.without(Field.CITY)).isEqualTo(FormFeedback.None)

        val until = Instant.parse("2026-11-08T10:00:30Z")
        assertThat(FormFeedback.from(DomainError.Locked(until)).lockedUntil).isEqualTo(until)
        assertThat(FormFeedback.from(DomainError.IncorrectPassword).message).isEqualTo(UiText.Resource(R.string.error_incorrect_password))
    }
}
