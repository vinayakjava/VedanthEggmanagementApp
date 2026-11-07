package com.eggbusinessmanager.domain.usecase.auth

import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.repository.SessionState
import com.eggbusinessmanager.testing.SignupForms
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SignInUseCaseTest {

    private val h = AuthHarness()
    private val ok = DomainResult.Ok(Unit)

    @Test
    fun `signs in with the mobile number in any common format`() = runTest {
        h.withAccount()
        listOf("9876543210", "+91 98765 43210", "098765-43210").forEach { id ->
            h.signOut()
            assertThat(h.signIn(id, SignupForms.PASSWORD)).isEqualTo(ok)
            assertThat(h.session.state.value).isEqualTo(SessionState.Unlocked())
        }
    }

    @Test
    fun `signs in with the email, case-insensitively`() = runTest {
        h.withAccount()
        assertThat(h.signIn("  OWNER@example.COM ", SignupForms.PASSWORD)).isEqualTo(ok)
    }

    @Test
    fun `wrong password and unknown identifier get the same generic error`() = runTest {
        h.withAccount()
        assertThat(h.signIn(SignupForms.MOBILE, "wrong-pass1")).isEqualTo(DomainResult.Err(DomainError.InvalidCredentials))
        assertThat(h.signIn("9000000000", SignupForms.PASSWORD)).isEqualTo(DomainResult.Err(DomainError.InvalidCredentials))
        assertThat(h.signIn("someone@else.com", SignupForms.PASSWORD)).isEqualTo(DomainResult.Err(DomainError.InvalidCredentials))
        assertThat(h.session.state.value).isEqualTo(SessionState.SignedOut)
    }

    @Test
    fun `the password is case-sensitive`() = runTest {
        h.withAccount()
        assertThat(h.signIn(SignupForms.MOBILE, SignupForms.PASSWORD.uppercase()))
            .isEqualTo(DomainResult.Err(DomainError.InvalidCredentials))
    }

    @Test
    fun `empty fields are validation errors and do not count as attempts`() = runTest {
        h.withAccount()
        val result = h.signIn(" ", "")

        val expected = mapOf(Field.IDENTIFIER to ValidationCode.REQUIRED, Field.PASSWORD to ValidationCode.REQUIRED)
        assertThat(result).isEqualTo(DomainResult.Err(DomainError.Validation(expected)))
        assertThat(h.accounts.current?.lockout?.failedAttempts).isEqualTo(0)
    }

    @Test
    fun `no account means sign-in reports NoAccount`() = runTest {
        assertThat(h.signIn(SignupForms.MOBILE, SignupForms.PASSWORD)).isEqualTo(DomainResult.Err(DomainError.NoAccount))
    }

    @Test
    fun `5th failure locks for 30 seconds, and even the right password is refused meanwhile`() = runTest {
        h.withAccount()
        h.failSignIn(4)

        val fifth = h.signIn(SignupForms.MOBILE, "wrong-pass1")
        val until = h.clock.now().plusSeconds(30)
        assertThat(fifth).isEqualTo(DomainResult.Err(DomainError.Locked(until)))

        h.clock.advanceBy(29)
        assertThat(h.signIn(SignupForms.MOBILE, SignupForms.PASSWORD)).isEqualTo(DomainResult.Err(DomainError.Locked(until)))
        assertThat(h.activeLockout()).isEqualTo(until)

        h.clock.advanceBy(1)
        assertThat(h.activeLockout()).isNull()
        assertThat(h.signIn(SignupForms.MOBILE, SignupForms.PASSWORD)).isEqualTo(ok)
    }

    @Test
    fun `attempts during a lock do not add failures`() = runTest {
        h.withAccount()
        h.failSignIn(5)
        h.failSignIn(3) // all refused as Locked
        assertThat(h.accounts.current?.lockout?.failedAttempts).isEqualTo(5)
    }

    @Test
    fun `each further 5 failures doubles the lock`() = runTest {
        h.withAccount()
        h.failSignIn(5)
        h.clock.advanceBy(30)
        h.failSignIn(4)

        val tenth = h.signIn(SignupForms.MOBILE, "wrong-pass1")

        assertThat(tenth).isEqualTo(DomainResult.Err(DomainError.Locked(h.clock.now().plusSeconds(60))))
    }

    @Test
    fun `a successful sign-in resets the counter`() = runTest {
        h.withAccount()
        h.failSignIn(4)
        h.signIn(SignupForms.MOBILE, SignupForms.PASSWORD)
        h.signOut()
        h.failSignIn(4)

        assertThat(h.accounts.current?.lockout?.failedAttempts).isEqualTo(4)
        assertThat(h.accounts.current?.lockout?.lockedUntil).isNull()
    }

    @Test
    fun `moving the clock backwards keeps the lock`() = runTest {
        h.withAccount()
        h.failSignIn(5)
        val until = checkNotNull(h.activeLockout())

        h.clock.advanceBy(-3_600)

        assertThat(h.signIn(SignupForms.MOBILE, SignupForms.PASSWORD)).isEqualTo(DomainResult.Err(DomainError.Locked(until)))
    }

    @Test
    fun `lockout state lives in the repository, so it survives an app restart`() = runTest {
        h.withAccount()
        h.failSignIn(5)

        // A fresh set of use cases over the same stored account (as after a process restart).
        val restarted = SignInUseCase(com.eggbusinessmanager.domain.auth.CredentialGate(h.accounts, h.clock), h.hasher, h.session)

        assertThat((restarted(SignupForms.MOBILE, SignupForms.PASSWORD) as DomainResult.Err).error)
            .isInstanceOf(DomainError.Locked::class.java)
    }

    @Test
    fun `unlock needs only the password and shares the lockout`() = runTest {
        h.withAccount()
        h.signIn(SignupForms.MOBILE, SignupForms.PASSWORD)
        h.session.lock()

        assertThat(h.unlock("")).isEqualTo(DomainResult.Err(DomainError.Validation(Field.PASSWORD, ValidationCode.REQUIRED)))
        assertThat(h.unlock("nope-nope1")).isEqualTo(DomainResult.Err(DomainError.IncorrectPassword))
        assertThat(h.session.state.value).isEqualTo(SessionState.Locked)
        assertThat(h.unlock(SignupForms.PASSWORD)).isEqualTo(ok)
        assertThat(h.session.state.value).isEqualTo(SessionState.Unlocked())
    }

    @Test
    fun `sign out returns to SignedOut and keeps the account`() = runTest {
        h.withAccount()
        h.signIn(SignupForms.MOBILE, SignupForms.PASSWORD)

        h.signOut()

        assertThat(h.session.state.value).isEqualTo(SessionState.SignedOut)
        assertThat(h.accounts.accountExists()).isTrue()
    }
}
