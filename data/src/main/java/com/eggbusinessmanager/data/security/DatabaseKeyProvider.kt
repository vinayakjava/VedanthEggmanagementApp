package com.eggbusinessmanager.data.security

import android.content.Context
import androidx.core.util.AtomicFile
import com.eggbusinessmanager.data.db.EggBusinessDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the 256-bit SQLCipher key: generated once on first launch, stored only wrapped by the
 * Keystore key [KEY_ALIAS] in `noBackupFilesDir`, never logged, never backed up (research R7).
 */
@Singleton
class DatabaseKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyWrapper: KeystoreKeyWrapper,
) {
    @Volatile
    private var cachedKey: ByteArray? = null

    private val keyFile: File
        get() = File(File(context.noBackupFilesDir, "keys"), "db.key")

    /** Returns the database key, creating it only when no database exists yet. */
    @Synchronized
    fun getOrCreateKey(): ByteArray {
        cachedKey?.let { return it }
        val key = if (keyFile.exists()) {
            readKey()
        } else {
            if (databaseFileExists()) {
                // A database without its key can never be opened; refuse to silently create a new one.
                throw KeyUnavailableException("Database exists but its key file is missing")
            }
            createKey()
        }
        cachedKey = key
        return key
    }

    private fun databaseFileExists(): Boolean = context.getDatabasePath(EggBusinessDatabase.FILE_NAME).exists()

    private fun createKey(): ByteArray {
        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val wrapped = keyWrapper.wrap(KEY_ALIAS, key)
        keyFile.parentFile?.mkdirs()
        val atomic = AtomicFile(keyFile)
        val out = atomic.startWrite()
        try {
            DataOutputStream(out).apply {
                writeByte(FORMAT_VERSION)
                writeByte(wrapped.iv.size)
                write(wrapped.iv)
                writeInt(wrapped.ciphertext.size)
                write(wrapped.ciphertext)
                flush()
            }
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
        return key
    }

    private fun readKey(): ByteArray {
        val wrapped = readWrappedKey()
            ?: throw KeyUnavailableException("Key file is unreadable or has an unknown format")
        val key = keyWrapper.unwrap(KEY_ALIAS, wrapped)
        if (key.size != KEY_BYTES) throw KeyUnavailableException("Stored database key has the wrong size")
        return key
    }

    /** Parses the key file; null when it is unreadable, truncated or of an unknown format version. */
    private fun readWrappedKey(): KeystoreKeyWrapper.Wrapped? = runCatching {
        DataInputStream(AtomicFile(keyFile).openRead()).use { input ->
            if (input.readByte().toInt() != FORMAT_VERSION) return@use null
            val iv = ByteArray(input.readByte().toInt()).also { input.readFully(it) }
            val ciphertext = ByteArray(input.readInt()).also { input.readFully(it) }
            KeystoreKeyWrapper.Wrapped(iv, ciphertext)
        }
    }.getOrNull()

    private companion object {
        const val KEY_ALIAS = "ebm_db_wrap"
        const val KEY_BYTES = 32
        const val FORMAT_VERSION = 1
    }
}
