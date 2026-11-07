package com.eggbusinessmanager.domain.usecase.auth

import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.model.account.SecurityAnswerInput
import com.eggbusinessmanager.domain.model.account.SecurityQuestion
import com.eggbusinessmanager.domain.repository.SessionState
import com.eggbusinessmanager.testing.SignupForms
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** US11: change password, recovery with PIN or security questions, changing recovery options. */
class PasswordManagementTest {

    private val h = AuthHarness()
    private val ok = DomainResult.Ok(Unit)
    private val newPassword = "fresh2025"

    // ---- Change password (FR-009) ----

    @Test
    fun `change password - new one works immediately and the old one is rejected`() = runTest {
        h.withAccount()

        assertThat(h.changePassword(SignupForms.PASSWORD, newPassword, newPassword)).isEqualTo(ok)

        assertThat(h.signIn(SignupForms.MOBILE, SignupForms.PASSWORD)).isEqualTo(DomainResult.Err(DomainError.InvalidCredentials))
        assertThat(h.signIn(SignupForms.MOBILE, newPassword)).isEqualTo(ok)
        assertThat(h.accounts.lastChangeAt).isEqualTo(h.clock.now())
    }

    @Test
    fun `change password - wrong current password is rejected and counts toward lockout`() = runTest {
        h.withAccount()

        assertThat(h.changePassword("not-mine1", newPassword, newPassword)).isEqualTo(DomainResult.Err(DomainError.IncorrectPassword))
        assertThat(h.accounts.current?.lockout?.failedAttempts).isEqualTo(1)
        repeat(4) { h.changePassword("not-mine1", newPassword, newPassword) }
        assertThat((h.changePassword(SignupForms.PASSWORD, newPassword, newPassword) as DomainResult.Err).error)
            .isInstanceOf(DomainError.Locked::class.java)
    }

    @Test
    fun `change password - new password rules apply, including not the mobile number`() = runTest {
        h.withAccount()

        val result = h.changePassword("", "short", "other")

        assertThat(result).isEqualTo(
            DomainResult.Err(
                DomainError.Validation(
                    mapOf(
                        Field.CURRENT_PASSWORD to ValidationCode.REQUIRED,
                        Field.PASSWORD to ValidationCode.WEAK_PASSWORD,
                        Field.CONFIRM_PASSWORD to ValidationCode.MISMATCH,
                    ),
                ),
            ),
        )
        assertThat(h.accounts.current?.lockout?.failedAttempts).isEqualTo(0)
    }

    @Test
    fun `credential changes need an account`() = runTest {
        assertThat(h.changePassword("a", "b", "c")).isEqualTo(DomainResult.Err(DomainError.NoAccount))
        assertThat(h.recoverWithPin("482916", newPassword, newPassword)).isEqualTo(DomainResult.Err(DomainError.NoAccount))
        assertThat(h.questions()).isEqualTo(DomainResult.Err(DomainError.NoAccount))
    }

    // ---- Recovery with PIN (FR-012a) ----

    @Test
    fun `recover with PIN sets the new password and signs in with the review prompt`() = runTest {
        h.withAccount()

        assertThat(h.recoverWithPin(SignupForms.RECOVERY_PIN, newPassword, newPassword)).isEqualTo(ok)

        assertThat(h.session.state.value).isEqualTo(SessionState.Unlocked(reviewRecovery = true))
        h.signOut()
        assertThat(h.signIn(SignupForms.MOBILE, newPassword)).isEqualTo(ok)
        assertThat(h.signIn(SignupForms.MOBILE, SignupForms.PASSWORD)).isEqualTo(DomainResult.Err(DomainError.InvalidCredentials))
    }

    @Test
    fun `recover with a wrong PIN changes nothing and counts toward lockout`() = runTest {
        h.withAccount()

        assertThat(h.recoverWithPin("111222", newPassword, newPassword)).isEqualTo(DomainResult.Err(DomainError.IncorrectRecoveryPin))

        assertThat(h.session.state.value).isEqualTo(SessionState.SignedOut)
        assertThat(h.accounts.current?.lockout?.failedAttempts).isEqualTo(1)
        assertThat(h.signIn(SignupForms.MOBILE, SignupForms.PASSWORD)).isEqualTo(ok)
    }

    @Test
    fun `recovery and sign-in share one lockout counter`() = runTest {
        h.withAccount()
        h.failSignIn(3)
        h.recoverWithPin("111222", newPassword, newPassword)

        val fifth = h.recoverWithPin("111223", newPassword, newPassword)

        assertThat((fifth as DomainResult.Err).error).isInstanceOf(DomainError.Locked::class.java)
    }

    @Test
    fun `recover with PIN validates the PIN field and the new password`() = runTest {
        h.withAccount()
        val result = h.recoverWithPin("", newPassword, "mismatch1")
        val expected = mapOf(Field.RECOVERY_PIN to ValidationCode.REQUIRED, Field.CONFIRM_PASSWORD to ValidationCode.MISMATCH)
        assertThat(result).isEqualTo(DomainResult.Err(DomainError.Validation(expected)))
    }

    // ---- Recovery with security questions (FR-012b) ----

    @Test
    fun `security questions are returned in slot order`() = runTest {
        h.withAccount()
        assertThat(h.questions()).isEqualTo(DomainResult.Ok(SignupForms.QUESTIONS))
    }

    @Test
    fun `recover with answers ignores case and spacing`() = runTest {
        h.withAccount()

        val result = h.recoverWithAnswers(listOf("  SARASWATI   vidyalaya", "nashik", "POHA "), newPassword, newPassword)

        assertThat(result).isEqualTo(ok)
        assertThat(h.session.state.value).isEqualTo(SessionState.Unlocked(reviewRecovery = true))
    }

    @Test
    fun `one wrong answer fails with a message that does not say which`() = runTest {
        h.withAccount()

        val result = h.recoverWithAnswers(listOf("Saraswati Vidyalaya", "Pune", "Poha"), newPassword, newPassword)

        assertThat(result).isEqualTo(DomainResult.Err(DomainError.IncorrectSecurityAnswers))
        assertThat(h.signIn(SignupForms.MOBILE, SignupForms.PASSWORD)).isEqualTo(ok)
    }

    @Test
    fun `blank answers are validation errors`() = runTest {
        h.withAccount()
        val result = h.recoverWithAnswers(listOf("x", " "), newPassword, newPassword)
        val fields = ((result as DomainResult.Err).error as DomainError.Validation).fields
        assertThat(fields).containsExactly(
            Field.SECURITY_ANSWER_2, ValidationCode.REQUIRED,
            Field.SECURITY_ANSWER_3, ValidationCode.REQUIRED,
        )
    }

    // ---- Changing recovery options (FR-013) ----

    @Test
    fun `change recovery PIN after confirming the password`() = runTest {
        h.withAccount()

        assertThat(h.changePin(SignupForms.PASSWORD, "790135", "790135")).isEqualTo(ok)

        assertThat(h.recoverWithPin(SignupForms.RECOVERY_PIN, newPassword, newPassword))
            .isEqualTo(DomainResult.Err(DomainError.IncorrectRecoveryPin))
        assertThat(h.recoverWithPin("790135", newPassword, newPassword)).isEqualTo(ok)
    }

    @Test
    fun `change recovery PIN rejects weak PINs and wrong passwords`() = runTest {
        h.withAccount()
        assertThat(h.changePin(SignupForms.PASSWORD, "123456", "123456"))
            .isEqualTo(DomainResult.Err(DomainError.Validation(Field.RECOVERY_PIN, ValidationCode.WEAK_PIN)))
        assertThat(h.changePin("wrong-pass1", "790135", "790135")).isEqualTo(DomainResult.Err(DomainError.IncorrectPassword))
    }

    @Test
    fun `update security questions replaces all three`() = runTest {
        h.withAccount()
        val newAnswers = listOf(
            SecurityAnswerInput(SecurityQuestion.FIRST_VEHICLE, "Hero Splendor"),
            SecurityAnswerInput(SecurityQuestion.FAVOURITE_FILM, "Sholay"),
            SecurityAnswerInput(SecurityQuestion.CHILDHOOD_FRIEND, "Ganesh"),
        )

        assertThat(h.updateQuestions(SignupForms.PASSWORD, newAnswers)).isEqualTo(ok)

        assertThat(h.questions()).isEqualTo(DomainResult.Ok(newAnswers.map { it.question }))
        assertThat(h.recoverWithAnswers(listOf("hero splendor", "SHOLAY", "ganesh"), newPassword, newPassword)).isEqualTo(ok)
    }

    @Test
    fun `update security questions validates against the current password`() = runTest {
        h.withAccount()
        val answers = listOf(
            SecurityAnswerInput(SecurityQuestion.FIRST_VEHICLE, SignupForms.PASSWORD),
            SecurityAnswerInput(SecurityQuestion.FIRST_VEHICLE, "Sholay"),
            SecurityAnswerInput(SecurityQuestion.CHILDHOOD_FRIEND, "Ganesh"),
        )

        val fields = ((h.updateQuestions(SignupForms.PASSWORD, answers) as DomainResult.Err).error as DomainError.Validation).fields

        assertThat(fields).containsExactly(
            Field.SECURITY_QUESTIONS, ValidationCode.DUPLICATE_QUESTIONS,
            Field.SECURITY_ANSWER_1, ValidationCode.ANSWER_EQUALS_PASSWORD,
        )
    }
}
