package com.eggbusinessmanager.app.feature.auth.biometric

import com.eggbusinessmanager.data.security.BiometricKeyManager
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.usecase.auth.DisableBiometricUseCase
import com.eggbusinessmanager.domain.usecase.auth.ObserveBiometricEnabledUseCase
import com.eggbusinessmanager.domain.usecase.auth.UnlockWithBiometricUseCase
import kotlinx.coroutines.flow.Flow
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * The app-side half of biometric unlock. Android `Cipher` objects cannot cross into the pure-Kotlin
 * domain, so this adapter prepares ciphers with [BiometricKeyManager] and hands the released token
 * to [UnlockWithBiometricUseCase], which makes the actual decision.
 */
class BiometricCoordinator @Inject constructor(
    private val keys: BiometricKeyManager,
    private val unlockWithBiometric: UnlockWithBiometricUseCase,
    observeEnabled: ObserveBiometricEnabledUseCase,
    private val disableBiometric: DisableBiometricUseCase,
) {
    val enabled: Flow<Boolean> = observeEnabled()
    val invalidatedNotice: Flow<Boolean> = keys.observeInvalidatedNotice()

    sealed interface Preparation {
        data class Ready(val cipher: Cipher) : Preparation
        data object Invalidated : Preparation
        data object NotEnabled : Preparation
    }

    suspend fun prepareUnlock(): Preparation = when (val result = keys.unlockCipher()) {
        is BiometricKeyManager.UnlockCipher.Ready -> Preparation.Ready(result.cipher)
        BiometricKeyManager.UnlockCipher.Invalidated -> Preparation.Invalidated
        BiometricKeyManager.UnlockCipher.NotEnabled -> Preparation.NotEnabled
    }

    suspend fun completeUnlock(authenticatedCipher: Cipher): DomainResult<Unit> {
        val token = keys.releaseToken(authenticatedCipher) ?: return DomainResult.Err(DomainError.InvalidCredentials)
        return unlockWithBiometric(token)
    }

    fun prepareEnrollment(): Cipher = keys.enrollmentCipher()

    suspend fun completeEnrollment(authenticatedCipher: Cipher) = keys.completeEnrollment(authenticatedCipher)

    suspend fun disable() = disableBiometric()

    suspend fun dismissInvalidatedNotice() = keys.clearInvalidatedNotice()
}
