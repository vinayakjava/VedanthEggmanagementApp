package com.eggbusinessmanager.data.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.eggbusinessmanager.domain.repository.BiometricUnlockStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Biometric unlock state (FR-008, research R8). A random token is encrypted with the auth-bound
 * Keystore key from [BiometricKeystore]; only a successful system biometric prompt can decrypt it,
 * and the token's SHA-256 is compared to unlock. Stored in device-only DataStore, never backed up.
 */
@Singleton
class BiometricKeyManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val keystore: BiometricKeystore,
) : BiometricUnlockStore {

    /** Result of preparing a cipher for the unlock prompt. */
    sealed interface UnlockCipher {
        data class Ready(val cipher: Cipher) : UnlockCipher

        /** Enrolled biometrics changed; biometric unlock was turned off and the owner must be told. */
        data object Invalidated : UnlockCipher

        data object NotEnabled : UnlockCipher
    }

    override fun observeEnabled(): Flow<Boolean> = dataStore.data.map { it[ENABLED] == true }

    /** True after biometric unlock was switched off because enrolled biometrics changed. */
    fun observeInvalidatedNotice(): Flow<Boolean> = dataStore.data.map { it[INVALIDATED_NOTICE] == true }

    override suspend fun isEnabled(): Boolean = observeEnabled().first()

    /** A fresh encrypt cipher over a new auth-bound key; pass it to the enrolment prompt. */
    fun enrollmentCipher(): Cipher = keystore.newEncryptCipher()

    /** Called with the cipher the enrolment prompt authenticated. */
    suspend fun completeEnrollment(authenticatedCipher: Cipher) {
        val token = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
        val ciphertext = authenticatedCipher.doFinal(token)
        dataStore.edit { prefs ->
            prefs[IV] = encode(authenticatedCipher.iv)
            prefs[CIPHERTEXT] = encode(ciphertext)
            prefs[TOKEN_SHA] = encode(sha256(token))
            prefs[ENABLED] = true
            prefs.remove(INVALIDATED_NOTICE)
        }
    }

    /** A decrypt cipher for the unlock prompt, or why there is none. */
    suspend fun unlockCipher(): UnlockCipher {
        val prefs = dataStore.data.first()
        val iv = prefs[IV]
        if (prefs[ENABLED] != true || iv == null) return UnlockCipher.NotEnabled
        return try {
            keystore.decryptCipher(decode(iv))?.let(UnlockCipher::Ready) ?: UnlockCipher.NotEnabled
        } catch (_: KeyPermanentlyInvalidatedException) {
            disable(invalidated = true)
            UnlockCipher.Invalidated
        }
    }

    /** Decrypts the token with the cipher the unlock prompt authenticated; null if it cannot. */
    suspend fun releaseToken(authenticatedCipher: Cipher): ByteArray? {
        val ciphertext = dataStore.data.first()[CIPHERTEXT] ?: return null
        return runCatching { authenticatedCipher.doFinal(decode(ciphertext)) }.getOrNull()
    }

    override suspend fun matchesToken(token: ByteArray): Boolean {
        val expected = dataStore.data.first()[TOKEN_SHA] ?: return false
        return MessageDigest.isEqual(sha256(token), decode(expected))
    }

    override suspend fun disable() = disable(invalidated = false)

    suspend fun clearInvalidatedNotice() {
        dataStore.edit { it.remove(INVALIDATED_NOTICE) }
    }

    private suspend fun disable(invalidated: Boolean) {
        dataStore.edit { prefs ->
            listOf(ENABLED, IV, CIPHERTEXT, TOKEN_SHA).forEach { prefs.remove(it) }
            if (invalidated) prefs[INVALIDATED_NOTICE] = true
        }
        keystore.delete()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(text: String) = Base64.getDecoder().decode(text)

    private companion object {
        const val TOKEN_BYTES = 32
        val ENABLED = booleanPreferencesKey("biometric_enabled")
        val IV = stringPreferencesKey("biometric_token_iv")
        val CIPHERTEXT = stringPreferencesKey("biometric_token_wrapped")
        val TOKEN_SHA = stringPreferencesKey("biometric_token_sha256")
        val INVALIDATED_NOTICE = booleanPreferencesKey("biometric_invalidated_notice")
    }
}
