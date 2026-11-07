package com.eggbusinessmanager.domain.auth

import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.model.account.AccountCredentials
import com.eggbusinessmanager.domain.model.account.LockoutState
import com.eggbusinessmanager.domain.repository.AccountRepository
import com.eggbusinessmanager.domain.repository.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place where a secret is checked against the stored account. It applies the shared
 * progressive lockout (FR-006) to sign-in, unlock, password change and both recovery methods, and
 * persists the counter so it survives restarts. Attempts are serialized so a double tap cannot
 * skip a failure.
 */
@Singleton
class CredentialGate @Inject constructor(
    private val accounts: AccountRepository,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /**
     * Runs [check]; on success resets the counter and runs [onSuccess], on failure records it and
     * returns [failure] (or [DomainError.Locked] when this failure starts a lock).
     */
    suspend fun <T> attempt(
        failure: DomainError,
        check: suspend (AccountCredentials) -> Boolean,
        onSuccess: suspend (AccountCredentials, Instant) -> T,
    ): DomainResult<T> = mutex.withLock {
        val credentials = accounts.credentials() ?: return@withLock DomainResult.Err(DomainError.NoAccount)
        val lockout = credentials.lockout
        val now = LockoutPolicy.effectiveNow(clock.now(), lockout.lastSeenAt)
        val activeLock = lockout.lockedUntil?.takeIf { now.isBefore(it) }
        when {
            activeLock != null -> DomainResult.Err(DomainError.Locked(activeLock))
            check(credentials) -> {
                accounts.updateLockout(LockoutState(failedAttempts = 0, lockedUntil = null, lastSeenAt = now))
                DomainResult.Ok(onSuccess(credentials, now))
            }
            else -> {
                val failures = lockout.failedAttempts + 1
                val until = LockoutPolicy.lockDurationAfter(failures)?.let(now::plus)
                accounts.updateLockout(LockoutState(failedAttempts = failures, lockedUntil = until, lastSeenAt = now))
                DomainResult.Err(if (until != null) DomainError.Locked(until) else failure)
            }
        }
    }

    /** The end of the current lock, or null when attempts are allowed (used to resume a countdown). */
    suspend fun activeLockUntil(): Instant? {
        val lockout = accounts.credentials()?.lockout ?: return null
        val now = LockoutPolicy.effectiveNow(clock.now(), lockout.lastSeenAt)
        return lockout.lockedUntil?.takeIf { now.isBefore(it) }
    }
}
