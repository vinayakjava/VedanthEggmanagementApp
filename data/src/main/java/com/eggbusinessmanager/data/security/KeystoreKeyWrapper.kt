package com.eggbusinessmanager.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps small secrets (database key, backup KEK) with AES-256-GCM keys that never leave the
 * Android Keystore (research R7, plan § Security Architecture).
 */
@Singleton
class KeystoreKeyWrapper @Inject constructor() {

    data class Wrapped(val iv: ByteArray, val ciphertext: ByteArray)

    fun wrap(alias: String, plaintext: ByteArray): Wrapped {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
        return Wrapped(iv = cipher.iv, ciphertext = cipher.doFinal(plaintext))
    }

    fun unwrap(alias: String, wrapped: Wrapped): ByteArray {
        val key = existingKey(alias) ?: throw KeyUnavailableException("Keystore key '$alias' is missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, wrapped.iv))
        return try {
            cipher.doFinal(wrapped.ciphertext)
        } catch (e: java.security.GeneralSecurityException) {
            throw KeyUnavailableException("Keystore key '$alias' cannot decrypt the stored secret", e)
        }
    }

    fun hasKey(alias: String): Boolean = existingKey(alias) != null

    private fun existingKey(alias: String): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun getOrCreateKey(alias: String): SecretKey = existingKey(alias) ?: run {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}

/** The key protecting local data cannot be used. Data is never deleted in response. */
class KeyUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
