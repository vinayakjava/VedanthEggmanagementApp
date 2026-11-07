package com.eggbusinessmanager.domain.usecase.auth

import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.catchingResult
import com.eggbusinessmanager.domain.repository.BiometricUnlockStore
import com.eggbusinessmanager.domain.repository.SessionManager
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Whether biometric unlock is turned on for this device (FR-008). */
class ObserveBiometricEnabledUseCase @Inject constructor(private val store: BiometricUnlockStore) {
    operator fun invoke(): Flow<Boolean> = store.observeEnabled()
}

class DisableBiometricUseCase @Inject constructor(private val store: BiometricUnlockStore) {
    suspend operator fun invoke() = store.disable()
}

/**
 * Completes a biometric unlock: [token] is the secret the Keystore released after the system
 * biometric prompt succeeded. A matching token authenticates the owner; the password is always
 * available as fallback.
 */
class UnlockWithBiometricUseCase @Inject constructor(
    private val store: BiometricUnlockStore,
    private val session: SessionManager,
) {
    suspend operator fun invoke(token: ByteArray): DomainResult<Unit> = catchingResult {
        if (store.isEnabled() && store.matchesToken(token)) {
            session.authenticated()
            DomainResult.Ok(Unit)
        } else {
            DomainResult.Err(DomainError.InvalidCredentials)
        }
    }
}
