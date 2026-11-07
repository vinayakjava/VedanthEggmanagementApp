package com.eggbusinessmanager.domain.usecase.auth

import com.eggbusinessmanager.domain.auth.CredentialGate
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.error.ValidationErrors
import com.eggbusinessmanager.domain.error.catchingResult
import com.eggbusinessmanager.domain.model.account.SecurityAnswerInput
import com.eggbusinessmanager.domain.model.account.StoredSecurityAnswer
import com.eggbusinessmanager.domain.repository.AccountRepository
import com.eggbusinessmanager.domain.repository.SecretHasher
import com.eggbusinessmanager.domain.validation.AccountValidator
import com.eggbusinessmanager.domain.validation.SecurityAnswerNormalizer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import javax.inject.Inject

/**
 * Runs a settings change that needs the current password: validates, verifies the password through
 * the lockout gate (a wrong password counts toward lockout, US11-2), then applies [apply].
 */
class PasswordConfirmedChange @Inject constructor(
    private val accounts: AccountRepository,
    private val gate: CredentialGate,
    private val hasher: SecretHasher,
) {
    suspend fun run(
        currentPassword: String,
        validate: (ValidationErrors, mobile: String) -> Unit,
        apply: suspend (Instant) -> Unit,
    ): DomainResult<Unit> = catchingResult {
        val credentials = accounts.credentials() ?: return DomainResult.Err(DomainError.NoAccount)
        val errors = ValidationErrors()
        if (currentPassword.isEmpty()) errors.add(Field.CURRENT_PASSWORD, ValidationCode.REQUIRED)
        validate(errors, credentials.mobile)
        errors.asFailure()?.let { return it }

        gate.attempt(
            failure = DomainError.IncorrectPassword,
            check = { hasher.verify(currentPassword, it.password) },
            onSuccess = { _, now -> apply(now) },
        )
    }
}

/** FR-009: change the password after confirming the current one. */
class ChangePasswordUseCase @Inject constructor(
    private val change: PasswordConfirmedChange,
    private val accounts: AccountRepository,
    private val hasher: SecretHasher,
) {
    suspend operator fun invoke(currentPassword: String, newPassword: String, confirmation: String): DomainResult<Unit> =
        change.run(
            currentPassword,
            validate = { errors, mobile -> AccountValidator.newPassword(errors, newPassword, confirmation, mobile) },
            apply = { now -> accounts.updatePassword(hasher.hash(newPassword), now) },
        )
}

/** FR-013: change the recovery PIN after confirming the current password. */
class ChangeRecoveryPinUseCase @Inject constructor(
    private val change: PasswordConfirmedChange,
    private val accounts: AccountRepository,
    private val hasher: SecretHasher,
) {
    suspend operator fun invoke(currentPassword: String, pin: String, confirmation: String): DomainResult<Unit> =
        change.run(
            currentPassword,
            validate = { errors, _ -> AccountValidator.newRecoveryPin(errors, pin, confirmation) },
            apply = { now -> accounts.updateRecoveryPin(hasher.hash(pin), now) },
        )
}

/** FR-013: replace the 3 security questions and answers after confirming the current password. */
class UpdateSecurityQuestionsUseCase @Inject constructor(
    private val change: PasswordConfirmedChange,
    private val accounts: AccountRepository,
    private val hasher: SecretHasher,
) {
    suspend operator fun invoke(currentPassword: String, answers: List<SecurityAnswerInput>): DomainResult<Unit> =
        change.run(
            currentPassword,
            validate = { errors, _ -> AccountValidator.securityAnswers(errors, answers, currentPassword) },
            apply = { now ->
                val stored = coroutineScope {
                    answers.map { input ->
                        async {
                            StoredSecurityAnswer(
                                question = checkNotNull(input.question),
                                answer = hasher.hash(SecurityAnswerNormalizer.normalize(input.answer)),
                            )
                        }
                    }.awaitAll()
                }
                accounts.replaceSecurityAnswers(stored, now)
            },
        )
}
