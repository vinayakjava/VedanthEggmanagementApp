package com.eggbusinessmanager.data.license

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.eggbusinessmanager.data.di.IoDispatcher
import com.eggbusinessmanager.domain.license.DeviceIdentity
import com.eggbusinessmanager.domain.license.LicenseFormat
import com.eggbusinessmanager.domain.license.LicenseStore
import com.eggbusinessmanager.domain.license.StoredLicense
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This phone's identity for licensing: an EC P-256 key pair generated inside the Android Keystore (the
 * private half never leaves it). Only the public key's [LicenseFormat.deviceHash] is used, shown to the
 * owner as the Device code. It lasts as long as the app's data on this phone; a reinstall or a new phone
 * gets a new identity and needs a new key.
 */
@Singleton
class KeystoreDeviceIdentity @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) : DeviceIdentity {
    private val lock = Mutex()

    override suspend fun deviceHash(): ByteArray = withContext(io) {
        lock.withLock { LicenseFormat.deviceHash(publicKey()) }
    }

    private fun publicKey(): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getCertificate(ALIAS)?.let { return it.publicKey.encoded }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKeyPair().public.encoded
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ebm_device_identity"
    }
}

/**
 * The activated key, in the app's private no-backup storage: it belongs to this phone, so it is never
 * part of a backup and never travels to another phone. The key is signed, so it cannot be altered here.
 */
@Singleton
class FileLicenseStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : LicenseStore {
    private val file: File get() = File(File(context.noBackupFilesDir, DIR), FILE)

    override suspend fun load(): StoredLicense? = withContext(io) {
        val lines = file.takeIf { it.exists() }?.readLines().orEmpty()
        val key = lines.getOrNull(0)?.takeIf { it.isNotBlank() }
        val at = lines.getOrNull(1)?.toLongOrNull()?.let(Instant::ofEpochMilli)
        if (key != null && at != null) StoredLicense(key, at) else null
    }

    override suspend fun save(license: StoredLicense) = withContext(io) {
        val target = file
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$FILE.tmp")
        tmp.writeText(license.key + "\n" + license.activatedAt.toEpochMilli() + "\n")
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not save the license" }
        }
    }

    companion object {
        const val DIR = "license"
        const val FILE = "license.txt"
    }
}
