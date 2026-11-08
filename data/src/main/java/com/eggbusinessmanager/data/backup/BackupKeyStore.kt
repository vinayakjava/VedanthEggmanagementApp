package com.eggbusinessmanager.data.backup

import android.content.Context
import com.eggbusinessmanager.data.backup.format.BackupCrypto
import com.eggbusinessmanager.data.security.KeystoreKeyWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the backup key-encryption key (research R9 step 2). The passphrase is never stored: the KEK is
 * derived from it once, wrapped by an Android Keystore key (`ebm_kek_wrap`), and kept with its salt and
 * iteration count in no-backup storage, so backups can be made later without typing the passphrase.
 * Changing the passphrase makes a new salt and KEK; older backups keep their own salt.
 */
@Singleton
class BackupKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wrapper: KeystoreKeyWrapper,
) {
    /** The KEK with the parameters it was derived with. */
    class Kek(val key: ByteArray, val salt: ByteArray, val iterations: Int)

    private val file: File get() = File(context.noBackupFilesDir, "backup/kek.bin")

    fun hasKek(): Boolean = file.exists() && wrapper.hasKey(ALIAS)

    /** Derives and stores a new KEK (slow: PBKDF2 with 600k iterations); call off the main thread. */
    fun set(passphrase: String, iterations: Int = BackupCrypto.ITERATIONS) {
        val salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES)
        val kek = BackupCrypto.deriveKek(passphrase, salt, iterations)
        try {
            val wrapped = wrapper.wrap(ALIAS, kek)
            val target = file.apply { parentFile?.mkdirs() }
            val temp = File(target.path + ".tmp")
            DataOutputStream(temp.outputStream()).use { out ->
                out.writeInt(VERSION)
                out.writeInt(iterations)
                out.writeBlob(salt)
                out.writeBlob(wrapped.iv)
                out.writeBlob(wrapped.ciphertext)
            }
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target)) { "Could not store the backup key" }
            }
        } finally {
            kek.fill(0)
        }
    }

    /** The stored KEK, or null when no passphrase has been set (or the Keystore key is gone). */
    fun get(): Kek? {
        if (!hasKek()) return null
        return try {
            DataInputStream(file.inputStream()).use { input ->
                check(input.readInt() == VERSION)
                val iterations = input.readInt()
                val salt = input.readBlob()
                val wrapped = KeystoreKeyWrapper.Wrapped(input.readBlob(), input.readBlob())
                Kek(wrapper.unwrap(ALIAS, wrapped), salt, iterations)
            }
        } catch (ignored: IOException) {
            null // unreadable key file: treated as "no passphrase set", so the owner sets it again
        }
    }

    private fun DataOutputStream.writeBlob(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBlob(): ByteArray {
        val size = readInt()
        require(size in 0..MAX_BLOB)
        return ByteArray(size).also(::readFully)
    }

    private companion object {
        const val ALIAS = "ebm_kek_wrap"
        const val VERSION = 1
        const val MAX_BLOB = 1024
    }
}
