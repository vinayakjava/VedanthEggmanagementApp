package com.eggbusinessmanager.domain.usecase.auth

import com.eggbusinessmanager.domain.auth.CredentialGate
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.error.ValidationErrors
import com.eggbusinessmanager.domain.error.catchingResult
import com.eggbusinessmanager.domain.model.account.AccountCredentials
import com.eggbusinessmanager.domain.repository.AccountRepository
import com.eggbusinessmanager.domain.repository.SecretHasher
import com.eggbusinessmanager.domain.repository.SessionManager
import com.eggbusinessmanager.domain.repository.SessionState
import com.eggbusinessmanager.domain.validation.FieldValidators
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import javax.inject.Inject

/**
 * Sign-in with the registered mobile number or email plus password (FR-003). Failures are generic
 * (FR-005) and count toward the shared lockout (FR-006).
 */
class SignInUseCase @Inject constructor(
    private val gate: CredentialGate,
    private val hasher: SecretHasher,
    private val session: SessionManager,
) {
    suspend operator fun invoke(identifier: String, password: String): DomainResult<Unit> = catchingResult {
        ValidationErrors().apply {
            if (identifier.isBlank()) add(Field.IDENTIFIER, ValidationCode.REQUIRED)
            if (password.isEmpty()) add(Field.PASSWORD, ValidationCode.REQUIRED)
        }.asFailure()?.let { return it }

        gate.attempt(
            failure = DomainError.InvalidCredentials,
            check = { credentials ->
                // Always verify the password, even for an unknown identifier, so timing reveals nothing.
                val passwordOk = hasher.verify(password, credentials.password)
                passwordOk && matchesIdentifier(identifier, credentials)
            },
            onSuccess = { _, _ -> session.authenticated() },
        )
    }

    private fun matchesIdentifier(identifier: String, credentials: AccountCredentials): Boolean {
        val email = FieldValidators.normalizeEmail(identifier)
        return FieldValidators.normalizeMobile(identifier) == credentials.mobile ||
            (credentials.email != null && email == credentials.email)
    }
}

/** Unlocks the auto-locked app with the password (US1-8). Same lockout as sign-in. */
class UnlockWithPasswordUseCase @Inject constructor(
    private val gate: CredentialGate,
    private val hasher: SecretHasher,
    private val session: SessionManager,
) {
    suspend operator fun invoke(password: String): DomainResult<Unit> = catchingResult {
        if (password.isEmpty()) return DomainResult.Err(DomainError.Validation(Field.PASSWORD, ValidationCode.REQUIRED))
        gate.attempt(
            failure = DomainError.IncorrectPassword,
            check = { hasher.verify(password, it.password) },
            onSuccess = { _, _ -> session.authenticated() },
        )
    }
}

/** Signs out; all data stays on the device (FR-011). */
class SignOutUseCase @Inject constructor(private val session: SessionManager) {
    operator fun invoke() = session.signOut()
}

class ObserveSessionUseCase @Inject constructor(private val session: SessionManager) {
    operator fun invoke(): StateFlow<SessionState> = session.state
}

class ObserveAccountExistsUseCase @Inject constructor(private val accounts: AccountRepository) {
    operator fun invoke(): Flow<Boolean> = accounts.observeAccountExists()
}

/** The end of an active lockout, so a countdown resumes after the app restarts (US1-6). */
class GetActiveLockoutUseCase @Inject constructor(private val gate: CredentialGate) {
    suspend operator fun invoke(): Instant? = gate.activeLockUntil()
}

/** Records user activity for the inactivity auto-lock. */
class RecordActivityUseCase @Inject constructor(private val session: SessionManager) {
    operator fun invoke() = session.touch()
}

/** Locks the app immediately (e.g. "Lock now"). */
class LockAppUseCase @Inject constructor(private val session: SessionManager) {
    operator fun invoke() = session.lock()
}
