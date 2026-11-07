package com.eggbusinessmanager.domain.usecase.auth

import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.model.account.BusinessProfile
import com.eggbusinessmanager.domain.model.account.IndianState
import com.eggbusinessmanager.domain.repository.SessionState
import com.eggbusinessmanager.domain.usecase.profile.ObserveBusinessProfileUseCase
import com.eggbusinessmanager.testing.FakeBiometricUnlockStore
import com.eggbusinessmanager.testing.FakeBusinessProfileRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SessionAndBiometricUseCasesTest {

    private val h = AuthHarness()

    @Test
    fun `biometric unlock succeeds only with the released token`() = runTest {
        h.withAccount()

        assertThat(h.unlockWithBiometric(byteArrayOf(9))).isEqualTo(DomainResult.Err(DomainError.InvalidCredentials))
        assertThat(h.session.state.value).isEqualTo(SessionState.SignedOut)

        assertThat(h.unlockWithBiometric(byteArrayOf(1, 2, 3))).isEqualTo(DomainResult.Ok(Unit))
        assertThat(h.session.state.value).isEqualTo(SessionState.Unlocked())
    }

    @Test
    fun `biometric unlock is refused once disabled`() = runTest {
        h.withAccount()
        DisableBiometricUseCase(h.biometric)()

        assertThat(ObserveBiometricEnabledUseCase(h.biometric)().first()).isFalse()
        assertThat(h.unlockWithBiometric(byteArrayOf(1, 2, 3))).isEqualTo(DomainResult.Err(DomainError.InvalidCredentials))
    }

    @Test
    fun `biometric store errors become Unexpected`() = runTest {
        val broken = object : com.eggbusinessmanager.domain.repository.BiometricUnlockStore by FakeBiometricUnlockStore(true) {
            override suspend fun matchesToken(token: ByteArray): Boolean = error("keystore")
        }
        val result = UnlockWithBiometricUseCase(broken, h.session)(byteArrayOf(1))
        assertThat((result as DomainResult.Err).error).isInstanceOf(DomainError.Unexpected::class.java)
    }

    @Test
    fun `session use cases delegate to the session manager`() = runTest {
        h.withAccount()
        h.signIn("9876543210", "eggs2024")

        RecordActivityUseCase(h.session)()
        assertThat(h.session.touches).isEqualTo(1)

        LockAppUseCase(h.session)()
        assertThat(ObserveSessionUseCase(h.session)().value).isEqualTo(SessionState.Locked)
    }

    @Test
    fun `post-recovery review prompt can be acknowledged`() = runTest {
        h.withAccount()
        h.recoverWithPin("482916", "fresh2025", "fresh2025")

        AcknowledgeRecoveryReviewUseCase(h.session)()

        assertThat(h.session.state.value).isEqualTo(SessionState.Unlocked(reviewRecovery = false))
    }

    @Test
    fun `account existence and business profile are observable`() = runTest {
        val exists = ObserveAccountExistsUseCase(h.accounts)
        assertThat(exists().first()).isFalse()
        h.withAccount()
        assertThat(exists().first()).isTrue()

        val profile = BusinessProfile("Shop", "Owner", null, "Pune", IndianState.MAHARASHTRA, "411001")
        val observe = ObserveBusinessProfileUseCase(FakeBusinessProfileRepository(profile))
        assertThat(observe().first()).isEqualTo(profile)
    }
}
