package com.eggbusinessmanager.domain.repository

import com.eggbusinessmanager.domain.model.account.AccountCredentials
import com.eggbusinessmanager.domain.model.account.BusinessProfile
import com.eggbusinessmanager.domain.model.account.HashedSecret
import com.eggbusinessmanager.domain.model.account.LockoutState
import com.eggbusinessmanager.domain.model.account.NewAccount
import com.eggbusinessmanager.domain.model.account.StoredSecurityAnswer
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** The single owner account of this installation (FR-001) and its protected secrets. */
interface AccountRepository {
    fun observeAccountExists(): Flow<Boolean>

    suspend fun accountExists(): Boolean

    /**
     * Stores the account, its security answers and the business profile in one transaction.
     * Returns false (and stores nothing) when an account already exists.
     */
    suspend fun create(account: NewAccount): Boolean

    /** Null when no account exists. */
    suspend fun credentials(): AccountCredentials?

    suspend fun updateLockout(state: LockoutState)

    suspend fun updatePassword(password: HashedSecret, at: Instant)

    suspend fun updateRecoveryPin(pin: HashedSecret, at: Instant)

    /** Replaces all 3 security answers atomically. */
    suspend fun replaceSecurityAnswers(answers: List<StoredSecurityAnswer>, at: Instant)
}

/** Shop details (data-model.md § business_profile). */
interface BusinessProfileRepository {
    fun observe(): Flow<BusinessProfile?>
}

/** PBKDF2 hashing of passwords, PINs and answers (research R8). Runs off the main thread. */
interface SecretHasher {
    suspend fun hash(secret: String): HashedSecret

    /** Constant-time comparison using the stored salt and iteration count. */
    suspend fun verify(secret: String, hashed: HashedSecret): Boolean
}
