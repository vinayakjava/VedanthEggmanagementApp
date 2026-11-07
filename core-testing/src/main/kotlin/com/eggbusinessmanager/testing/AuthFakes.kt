package com.eggbusinessmanager.testing

import com.eggbusinessmanager.domain.model.account.AccountCredentials
import com.eggbusinessmanager.domain.model.account.BusinessProfile
import com.eggbusinessmanager.domain.model.account.HashedSecret
import com.eggbusinessmanager.domain.model.account.IndianState
import com.eggbusinessmanager.domain.model.account.LockoutState
import com.eggbusinessmanager.domain.model.account.NewAccount
import com.eggbusinessmanager.domain.model.account.SecurityAnswerInput
import com.eggbusinessmanager.domain.model.account.SecurityQuestion
import com.eggbusinessmanager.domain.model.account.SignupForm
import com.eggbusinessmanager.domain.model.account.StoredSecurityAnswer
import com.eggbusinessmanager.domain.repository.AccountRepository
import com.eggbusinessmanager.domain.repository.BiometricUnlockStore
import com.eggbusinessmanager.domain.repository.BusinessProfileRepository
import com.eggbusinessmanager.domain.repository.SecretHasher
import com.eggbusinessmanager.domain.repository.SessionManager
import com.eggbusinessmanager.domain.repository.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * Fast, deterministic stand-in for PBKDF2: "hash" = salt-tagged UTF-8 bytes. Never use outside tests.
 * Counts calls so tests can assert that secrets were (or were not) hashed.
 */
class FakeSecretHasher : SecretHasher {
    var hashCalls = 0
        private set
    private var nextSalt = 0

    override suspend fun hash(secret: String): HashedSecret {
        hashCalls++
        val salt = byteArrayOf((nextSalt++).toByte())
        return HashedSecret(hash = salt + secret.toByteArray(), salt = salt, iterations = 1)
    }

    override suspend fun verify(secret: String, hashed: HashedSecret): Boolean =
        (hashed.salt + secret.toByteArray()).contentEquals(hashed.hash)
}

/** In-memory owner account with the same singleton semantics as the Room implementation. */
class FakeAccountRepository : AccountRepository {
    private val stored = MutableStateFlow<AccountCredentials?>(null)
    var profile: BusinessProfile? = null
        private set
    var lastChangeAt: Instant? = null
        private set

    val current: AccountCredentials? get() = stored.value

    override fun observeAccountExists(): Flow<Boolean> = stored.map { it != null }

    override suspend fun accountExists(): Boolean = stored.value != null

    override suspend fun create(account: NewAccount): Boolean {
        if (stored.value != null) return false
        stored.value = AccountCredentials(
            mobile = account.mobile,
            email = account.email,
            password = account.password,
            recoveryPin = account.recoveryPin,
            securityAnswers = account.securityAnswers,
            lockout = LockoutState(failedAttempts = 0, lockedUntil = null, lastSeenAt = account.createdAt),
        )
        profile = account.profile
        return true
    }

    override suspend fun credentials(): AccountCredentials? = stored.value

    override suspend fun updateLockout(state: LockoutState) = update { it.copy(lockout = state) }

    override suspend fun updatePassword(password: HashedSecret, at: Instant) = update(at) { it.copy(password = password) }

    override suspend fun updateRecoveryPin(pin: HashedSecret, at: Instant) = update(at) { it.copy(recoveryPin = pin) }

    override suspend fun replaceSecurityAnswers(answers: List<StoredSecurityAnswer>, at: Instant) =
        update(at) { it.copy(securityAnswers = answers) }

    private fun update(at: Instant? = null, transform: (AccountCredentials) -> AccountCredentials) {
        stored.value = transform(checkNotNull(stored.value) { "No account" })
        if (at != null) lastChangeAt = at
    }
}

class FakeBusinessProfileRepository(initial: BusinessProfile? = null) : BusinessProfileRepository {
    val profile = MutableStateFlow(initial)
    override fun observe(): Flow<BusinessProfile?> = profile
}

/** Session state machine without timers; tests drive it directly. */
class FakeSessionManager(initial: SessionState = SessionState.SignedOut) : SessionManager {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<SessionState> = _state.asStateFlow()
    var touches = 0
        private set

    override fun authenticated(afterRecovery: Boolean) {
        _state.value = SessionState.Unlocked(reviewRecovery = afterRecovery)
    }

    override fun lock() {
        if (_state.value is SessionState.Unlocked) _state.value = SessionState.Locked
    }

    override fun signOut() {
        _state.value = SessionState.SignedOut
    }

    override fun touch() {
        touches++
    }

    override fun recoveryReviewed() {
        (_state.value as? SessionState.Unlocked)?.let { _state.value = it.copy(reviewRecovery = false) }
    }
}

class FakeBiometricUnlockStore(enabled: Boolean = false, private val token: ByteArray = byteArrayOf(1, 2, 3)) :
    BiometricUnlockStore {
    private val enabledFlow = MutableStateFlow(enabled)
    override fun observeEnabled(): Flow<Boolean> = enabledFlow
    override suspend fun isEnabled(): Boolean = enabledFlow.value
    override suspend fun matchesToken(token: ByteArray): Boolean = this.token.contentEquals(token)
    override suspend fun disable() {
        enabledFlow.value = false
    }
}

/** A complete, valid signup used across tests. Override fields with `copy`. */
object SignupForms {
    const val MOBILE = "9876543210"
    const val EMAIL = "owner@example.com"
    const val PASSWORD = "eggs2024"
    const val RECOVERY_PIN = "482916"
    val QUESTIONS = listOf(SecurityQuestion.FIRST_SCHOOL, SecurityQuestion.BIRTH_TOWN, SecurityQuestion.FAVOURITE_DISH)
    val ANSWERS = listOf("Saraswati Vidyalaya", "Nashik", "Poha")

    val valid = SignupForm(
        ownerName = "Ramesh Patil",
        mobile = "+91 98765-43210",
        email = "Owner@Example.com",
        password = PASSWORD,
        confirmPassword = PASSWORD,
        shopName = "Patil Egg Centre",
        address = "Shop 4, Market Yard",
        city = "Nashik",
        state = IndianState.MAHARASHTRA,
        pinCode = "422001",
        recoveryPin = RECOVERY_PIN,
        confirmRecoveryPin = RECOVERY_PIN,
        securityAnswers = QUESTIONS.zip(ANSWERS) { q, a -> SecurityAnswerInput(q, a) },
    )
}
