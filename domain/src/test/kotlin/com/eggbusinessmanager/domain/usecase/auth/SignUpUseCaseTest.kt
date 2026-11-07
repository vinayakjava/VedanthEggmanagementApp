package com.eggbusinessmanager.domain.usecase.auth

import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.model.account.IndianState
import com.eggbusinessmanager.domain.model.account.NewAccount
import com.eggbusinessmanager.domain.repository.AccountRepository
import com.eggbusinessmanager.domain.repository.SessionState
import com.eggbusinessmanager.testing.FakeAccountRepository
import com.eggbusinessmanager.testing.SignupForms
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SignUpUseCaseTest {

    private val h = AuthHarness()

    @Test
    fun `valid signup stores account, profile and protected secrets, then signs in`() = runTest {
        val result = h.signUp(SignupForms.valid)

        assertThat(result).isEqualTo(DomainResult.Ok(Unit))
        val stored = checkNotNull(h.accounts.current)
        assertThat(stored.mobile).isEqualTo("9876543210")
        assertThat(stored.email).isEqualTo("owner@example.com")
        assertThat(stored.securityAnswers.map { it.question }).isEqualTo(SignupForms.QUESTIONS)
        assertThat(h.accounts.profile?.city).isEqualTo("Nashik")
        assertThat(h.accounts.profile?.state).isEqualTo(IndianState.MAHARASHTRA)
        assertThat(h.accounts.profile?.pinCode).isEqualTo("422001")
        assertThat(h.session.state.value).isEqualTo(SessionState.Unlocked())
    }

    @Test
    fun `password, PIN and answers are hashed, never stored as typed`() = runTest {
        h.signUp(SignupForms.valid)

        val stored = checkNotNull(h.accounts.current)
        assertThat(h.hasher.hashCalls).isEqualTo(5)
        assertThat(h.hasher.verify(SignupForms.PASSWORD, stored.password)).isTrue()
        assertThat(h.hasher.verify(SignupForms.RECOVERY_PIN, stored.recoveryPin)).isTrue()
        // Answers are normalized before hashing, so case and spacing do not matter at recovery.
        assertThat(h.hasher.verify("nashik", stored.securityAnswers[1].answer)).isTrue()
    }

    @Test
    fun `nothing about the account or its secrets is logged`() = runTest {
        h.signUp(SignupForms.valid)

        val log = h.logger.lines.joinToString("\n")
        listOf(SignupForms.PASSWORD, SignupForms.RECOVERY_PIN, "9876543210", "Nashik").forEach {
            assertThat(log).doesNotContain(it)
        }
    }

    @Test
    fun `invalid input is rejected without hashing or storing anything`() = runTest {
        val result = h.signUp(SignupForms.valid.copy(pinCode = "12", confirmPassword = "different1"))

        val fields = ((result as DomainResult.Err).error as DomainError.Validation).fields
        assertThat(fields).containsExactly(
            Field.CONFIRM_PASSWORD, ValidationCode.MISMATCH,
            Field.PIN_CODE, ValidationCode.INVALID_FORMAT,
        )
        assertThat(h.accounts.current).isNull()
        assertThat(h.hasher.hashCalls).isEqualTo(0)
        assertThat(h.session.state.value).isEqualTo(SessionState.SignedOut)
    }

    @Test
    fun `signup is refused once an account exists (one account per installation)`() = runTest {
        h.signUp(SignupForms.valid)
        h.signOut()

        val second = h.signUp(SignupForms.valid.copy(mobile = "9123456789"))

        assertThat(second).isEqualTo(DomainResult.Err(DomainError.AccountAlreadyExists))
        assertThat(h.accounts.current?.mobile).isEqualTo("9876543210")
    }

    @Test
    fun `losing a race to create the account reports AccountAlreadyExists`() = runTest {
        val racing = object : AccountRepository by FakeAccountRepository() {
            override suspend fun accountExists() = false
            override suspend fun create(account: NewAccount) = false
        }
        val useCase = SignUpUseCase(racing, h.hasher, h.clock, h.session, h.logger)

        assertThat(useCase(SignupForms.valid)).isEqualTo(DomainResult.Err(DomainError.AccountAlreadyExists))
        assertThat(h.session.state.value).isEqualTo(SessionState.SignedOut)
    }

    @Test
    fun `storage failure becomes Unexpected instead of crashing`() = runTest {
        val broken = object : AccountRepository by FakeAccountRepository() {
            override suspend fun create(account: NewAccount): Boolean = error("disk full")
        }
        val useCase = SignUpUseCase(broken, h.hasher, h.clock, h.session, h.logger)

        val result = useCase(SignupForms.valid)

        assertThat((result as DomainResult.Err).error).isInstanceOf(DomainError.Unexpected::class.java)
        assertThat(h.session.state.value).isEqualTo(SessionState.SignedOut)
    }
}
