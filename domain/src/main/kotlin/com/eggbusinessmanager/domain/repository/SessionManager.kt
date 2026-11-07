package com.eggbusinessmanager.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Whether the owner may see business data right now. Held in memory only. */
sealed interface SessionState {
    /** Nobody is signed in: the sign-in (or welcome) screen is shown. Also the state after a restart. */
    data object SignedOut : SessionState

    /** Signed in, but the app locked itself after inactivity; screens stay composed behind the lock (US1-8). */
    data object Locked : SessionState

    /** Signed in and usable. [reviewRecovery] asks the owner to review recovery options after a reset (FR-013). */
    data class Unlocked(val reviewRecovery: Boolean = false) : SessionState
}

/** Lock state and auto-lock (research R8). */
interface SessionManager {
    val state: StateFlow<SessionState>

    /** Called after successful authentication (sign-in, signup, recovery, unlock). */
    fun authenticated(afterRecovery: Boolean = false)

    fun lock()

    fun signOut()

    /** Records user activity; resets the inactivity timer. */
    fun touch()

    /** Clears the post-recovery review prompt. */
    fun recoveryReviewed()
}

/**
 * Device-only biometric unlock state (data-model.md § Device-only state). Enrolment needs an Android
 * `Cipher`, so it lives in :data/:app; the domain only checks the decrypted token.
 */
interface BiometricUnlockStore {
    fun observeEnabled(): Flow<Boolean>

    suspend fun isEnabled(): Boolean

    /** True when [token] is the secret released by a successful biometric prompt. */
    suspend fun matchesToken(token: ByteArray): Boolean

    suspend fun disable()
}
