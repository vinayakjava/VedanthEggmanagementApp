package com.eggbusinessmanager.domain.usecase.auth

import com.eggbusinessmanager.domain.auth.CredentialGate
import com.eggbusinessmanager.domain.backup.RestoreProof
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.error.ValidationErrors
import com.eggbusinessmanager.domain.error.catchingResult
import com.eggbusinessmanager.domain.model.account.AccountCredentials
import com.eggbusinessmanager.domain.model.account.SecurityQuestion
import com.eggbusinessmanager.domain.repository.AccountRepository
import com.eggbusinessmanager.domain.repository.SecretHasher
import com.eggbusinessmanager.domain.repository.SessionManager
import com.eggbusinessmanager.domain.validation.AccountValidator
import com.eggbusinessmanager.domain.validation.SecurityAnswerNormalizer
import javax.inject.Inject

/**
 * Shared recovery flow (FR-012/013): validate the new password, verify the recovery secret through
 * the lockout gate, store the new password and sign in with the "review recovery options" prompt.
 */
class PasswordResetter @Inject constructor(
    private val accounts: AccountRepository,
    private val gate: CredentialGate,
    private val hasher: SecretHasher,
    private val session: SessionManager,
) {
    suspend fun reset(
        newPassword: String,
        confirmation: String,
        secretErrors: ValidationErrors,
        failure: DomainError,
        verify: suspend (AccountCredentials) -> Boolean,
    ): DomainResult<Unit> = catchingResult {
        val credentials = accounts.credentials() ?: return DomainResult.Err(DomainError.NoAccount)
        AccountValidator.newPassword(secretErrors, newPassword, confirmation, credentials.mobile)
        secretErrors.asFailure()?.let { return it }

        gate.attempt(failure = failure, check = verify) { _, now ->
            accounts.updatePassword(hasher.hash(newPassword), now)
            session.authenticated(afterRecovery = true)
        }
    }
}

/**
 * US9-9: right after restoring a backup — which needed the backup passphrase — the owner may set a new
 * login password without the old one, once. Validation failures leave the chance open.
 */
class SetPasswordAfterRestoreUseCase @Inject constructor(
    private val resetter: PasswordResetter,
    private val proof: RestoreProof,
) {
    suspend operator fun invoke(newPassword: String, confirmation: String): DomainResult<Unit> {
        if (!proof.isGranted) return DomainResult.Err(DomainError.RestoreProofMissing)
        return resetter.reset(newPassword, confirmation, ValidationErrors(), DomainError.RestoreProofMissing) { true }
            .also { if (it is DomainResult.Ok) proof.clear() }
    }
}

/** Recovery with the 6-digit recovery PIN. */
class RecoverWithPinUseCase @Inject constructor(
    private val resetter: PasswordResetter,
    private val hasher: SecretHasher,
) {
    suspend operator fun invoke(pin: String, newPassword: String, confirmation: String): DomainResult<Unit> {
        val errors = ValidationErrors()
        if (pin.isEmpty()) errors.add(Field.RECOVERY_PIN, ValidationCode.REQUIRED)
        return resetter.reset(newPassword, confirmation, errors, DomainError.IncorrectRecoveryPin) {
            hasher.verify(pin, it.recoveryPin)
        }
    }
}

/** Recovery by answering all 3 security questions; never reveals which answer was wrong. */
class RecoverWithSecurityAnswersUseCase @Inject constructor(
    private val resetter: PasswordResetter,
    private val hasher: SecretHasher,
) {
    /** [answers] are in the order returned by [GetSecurityQuestionsUseCase]. */
    suspend operator fun invoke(answers: List<String>, newPassword: String, confirmation: String): DomainResult<Unit> {
        val errors = ValidationErrors()
        repeat(SecurityQuestion.REQUIRED_COUNT) { index ->
            if (answers.getOrNull(index).isNullOrBlank()) errors.add(Field.securityAnswer(index), ValidationCode.REQUIRED)
        }
        return resetter.reset(newPassword, confirmation, errors, DomainError.IncorrectSecurityAnswers) { credentials ->
            val stored = credentials.securityAnswers
            // Verify every answer (no short-circuit) so the time taken does not hint at which one failed.
            val results = stored.mapIndexed { index, item ->
                hasher.verify(SecurityAnswerNormalizer.normalize(answers[index]), item.answer)
            }
            stored.size == SecurityQuestion.REQUIRED_COUNT && results.all { it }
        }
    }
}

/** The owner's 3 chosen questions, in slot order (shown on the recovery screen). */
class GetSecurityQuestionsUseCase @Inject constructor(private val accounts: AccountRepository) {
    suspend operator fun invoke(): DomainResult<List<SecurityQuestion>> = catchingResult {
        val credentials = accounts.credentials() ?: return DomainResult.Err(DomainError.NoAccount)
        DomainResult.Ok(credentials.securityAnswers.map { it.question })
    }
}

/** Clears the post-recovery "review your recovery options" prompt (FR-013). */
class AcknowledgeRecoveryReviewUseCase @Inject constructor(private val session: SessionManager) {
    operator fun invoke() = session.recoveryReviewed()
}
