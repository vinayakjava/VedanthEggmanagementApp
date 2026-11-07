package com.eggbusinessmanager.data.security

import com.eggbusinessmanager.data.di.DefaultDispatcher
import com.eggbusinessmanager.domain.model.account.HashedSecret
import com.eggbusinessmanager.domain.repository.SecretHasher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject

/**
 * PBKDF2-HMAC-SHA256 with a 16-byte random salt per secret and 600,000 iterations (research R8,
 * OWASP guidance). Runs on the Default dispatcher; about 0.5–1 s per secret on a mid-range phone.
 * Verification uses the iterations stored with the hash, so the cost can be raised later.
 */
class Pbkdf2SecretHasher internal constructor(
    private val dispatcher: CoroutineDispatcher,
    private val iterations: Int,
) : SecretHasher {

    @Inject
    constructor(@DefaultDispatcher dispatcher: CoroutineDispatcher) : this(dispatcher, DEFAULT_ITERATIONS)

    private val random = SecureRandom()

    override suspend fun hash(secret: String): HashedSecret = withContext(dispatcher) {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        HashedSecret(hash = derive(secret, salt, iterations), salt = salt, iterations = iterations)
    }

    override suspend fun verify(secret: String, hashed: HashedSecret): Boolean = withContext(dispatcher) {
        MessageDigest.isEqual(derive(secret, hashed.salt, hashed.iterations), hashed.hash)
    }

    private fun derive(secret: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, rounds, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val DEFAULT_ITERATIONS = 600_000
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
    }
}
