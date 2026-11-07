package com.eggbusinessmanager.domain.usecase.auth

import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.catchingResult
import com.eggbusinessmanager.domain.logging.AppLogger
import com.eggbusinessmanager.domain.model.account.NewAccount
import com.eggbusinessmanager.domain.model.account.SignupForm
import com.eggbusinessmanager.domain.model.account.StoredSecurityAnswer
import com.eggbusinessmanager.domain.model.account.ValidSignup
import com.eggbusinessmanager.domain.repository.AccountRepository
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.SecretHasher
import com.eggbusinessmanager.domain.repository.SessionManager
import com.eggbusinessmanager.domain.validation.SignupValidator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject

/**
 * Creates the one owner account (FR-001/002/012): validates every field, hashes the password, the
 * recovery PIN and the 3 answers, stores account + profile + answers atomically, and signs in.
 */
class SignUpUseCase @Inject constructor(
    private val accounts: AccountRepository,
    private val hasher: SecretHasher,
    private val clock: Clock,
    private val session: SessionManager,
    private val logger: AppLogger,
) {
    suspend operator fun invoke(form: SignupForm): DomainResult<Unit> = catchingResult {
        if (accounts.accountExists()) return DomainResult.Err(DomainError.AccountAlreadyExists)
        val valid = when (val result = SignupValidator.signup(form)) {
            is DomainResult.Err -> return result
            is DomainResult.Ok -> result.value
        }
        if (!accounts.create(protect(valid))) return DomainResult.Err(DomainError.AccountAlreadyExists)
        logger.info(TAG) { "Account created" }
        session.authenticated()
        DomainResult.Ok(Unit)
    }

    /** Hashes all five secrets in parallel; each PBKDF2 run takes most of a second on a phone. */
    private suspend fun protect(valid: ValidSignup): NewAccount = coroutineScope {
        val password = async { hasher.hash(valid.password) }
        val pin = async { hasher.hash(valid.recoveryPin) }
        val answers = valid.securityAnswers.map { (question, answer) ->
            async { StoredSecurityAnswer(question, hasher.hash(answer)) }
        }
        NewAccount(
            mobile = valid.mobile,
            email = valid.email,
            password = password.await(),
            recoveryPin = pin.await(),
            securityAnswers = answers.awaitAll(),
            profile = valid.profile,
            createdAt = clock.now(),
        )
    }

    private companion object {
        const val TAG = "SignUp"
    }
}
