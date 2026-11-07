package com.eggbusinessmanager.domain.usecase.auth

import com.eggbusinessmanager.domain.auth.CredentialGate
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.testing.FakeAccountRepository
import com.eggbusinessmanager.testing.FakeBiometricUnlockStore
import com.eggbusinessmanager.testing.FakeSecretHasher
import com.eggbusinessmanager.testing.FakeSessionManager
import com.eggbusinessmanager.testing.FixedClock
import com.eggbusinessmanager.testing.RecordingLogger
import com.eggbusinessmanager.testing.SignupForms
import com.google.common.truth.Truth.assertThat

/** Wires every auth use case to in-memory fakes, like Hilt does with the real implementations. */
class AuthHarness {
    val clock = FixedClock()
    val accounts = FakeAccountRepository()
    val hasher = FakeSecretHasher()
    val session = FakeSessionManager()
    val logger = RecordingLogger()
    val biometric = FakeBiometricUnlockStore(enabled = true)
    val gate = CredentialGate(accounts, clock)

    val signUp = SignUpUseCase(accounts, hasher, clock, session, logger)
    val signIn = SignInUseCase(gate, hasher, session)
    val unlock = UnlockWithPasswordUseCase(gate, hasher, session)
    val signOut = SignOutUseCase(session)
    val activeLockout = GetActiveLockoutUseCase(gate)

    private val resetter = PasswordResetter(accounts, gate, hasher, session)
    val recoverWithPin = RecoverWithPinUseCase(resetter, hasher)
    val recoverWithAnswers = RecoverWithSecurityAnswersUseCase(resetter, hasher)
    val questions = GetSecurityQuestionsUseCase(accounts)

    private val confirmed = PasswordConfirmedChange(accounts, gate, hasher)
    val changePassword = ChangePasswordUseCase(confirmed, accounts, hasher)
    val changePin = ChangeRecoveryPinUseCase(confirmed, accounts, hasher)
    val updateQuestions = UpdateSecurityQuestionsUseCase(confirmed, accounts, hasher)

    val unlockWithBiometric = UnlockWithBiometricUseCase(biometric, session)

    /** Creates the standard account from [SignupForms.valid] and signs out, ready for sign-in tests. */
    suspend fun withAccount(): AuthHarness {
        assertThat(signUp(SignupForms.valid)).isEqualTo(DomainResult.Ok(Unit))
        signOut()
        return this
    }

    /** Fails sign-in [times] times with a wrong password. */
    suspend fun failSignIn(times: Int) = repeat(times) { signIn(SignupForms.MOBILE, "wrong-pass1") }
}
