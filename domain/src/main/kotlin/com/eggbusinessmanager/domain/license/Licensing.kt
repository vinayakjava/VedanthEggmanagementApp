package com.eggbusinessmanager.domain.license

import com.eggbusinessmanager.domain.repository.Clock
import java.security.PublicKey
import java.time.Instant
import javax.inject.Inject

/** This phone's identity: a key pair created in the Android Keystore; only its public half is ever read. */
interface DeviceIdentity {
    /** [LicenseFormat.deviceHash] of this phone's identity, created on first use. */
    suspend fun deviceHash(): ByteArray
}

/** Where the activated key is kept on this phone (never in backups: it belongs to this phone). */
interface LicenseStore {
    suspend fun load(): StoredLicense?

    suspend fun save(license: StoredLicense)
}

data class StoredLicense(val key: String, val activatedAt: Instant)

/** The owner's public verification key, the only licensing secret-free material inside the app. */
class LicensePublicKey(val key: PublicKey)

/** A verified lifetime license on this phone. */
data class ActiveLicense(val licenseId: String, val maxDevices: Int, val activatedAt: Instant)

sealed interface LicenseCheck {
    data class Valid(val payload: LicenseFormat.Payload) : LicenseCheck

    /** Not a license key at all (typo, cut off while copying). */
    data object Malformed : LicenseCheck

    /** Looks like a key but was not signed by the owner, or was altered. */
    data object NotGenuine : LicenseCheck

    /** A genuine key, made for another phone. */
    data object OtherDevice : LicenseCheck
}

/** Offline verification: signature with the owner's public key, then the device binding. */
class LicenseVerifier @Inject constructor(private val publicKey: LicensePublicKey) {
    fun check(key: String, deviceHash: ByteArray): LicenseCheck {
        val decoded = LicenseFormat.decodeKey(key) ?: return LicenseCheck.Malformed
        return when {
            !LicenseCrypto.verify(publicKey.key, decoded.signedBytes, decoded.signature) -> LicenseCheck.NotGenuine
            !decoded.payload.deviceHash.contentEquals(deviceHash) -> LicenseCheck.OtherDevice
            else -> LicenseCheck.Valid(decoded.payload)
        }
    }
}

/** Activation status: checked offline at every start; no license key is asked for again once activated. */
sealed interface LicenseStatus {
    data class Activated(val license: ActiveLicense) : LicenseStatus

    /** Never activated, or the saved key no longer matches this phone (e.g. after moving to a new phone). */
    data object NotActivated : LicenseStatus
}

/** This phone's Device code, to send to the owner. */
class GetDeviceCodeUseCase @Inject constructor(private val identity: DeviceIdentity) {
    suspend operator fun invoke(): String = LicenseFormat.deviceCode(identity.deviceHash())
}

/**
 * Re-verifies the saved key against this phone. A failure only means the Activation screen is shown
 * again; business data is never touched.
 */
class CheckLicenseUseCase @Inject constructor(
    private val store: LicenseStore,
    private val identity: DeviceIdentity,
    private val verifier: LicenseVerifier,
) {
    suspend operator fun invoke(): LicenseStatus {
        val saved = store.load() ?: return LicenseStatus.NotActivated
        val check = verifier.check(saved.key, identity.deviceHash())
        return if (check is LicenseCheck.Valid) {
            LicenseStatus.Activated(ActiveLicense(check.payload.licenseIdText, check.payload.maxDevices, saved.activatedAt))
        } else {
            LicenseStatus.NotActivated
        }
    }
}

/** Verifies a pasted key for this phone and saves it; nothing is saved unless it is valid. */
class ActivateLicenseUseCase @Inject constructor(
    private val store: LicenseStore,
    private val identity: DeviceIdentity,
    private val verifier: LicenseVerifier,
    private val clock: Clock,
) {
    sealed interface Result {
        data class Activated(val license: ActiveLicense) : Result

        data class Rejected(val reason: LicenseCheck) : Result
    }

    suspend operator fun invoke(key: String): Result {
        val trimmed = key.trim()
        return when (val check = verifier.check(trimmed, identity.deviceHash())) {
            is LicenseCheck.Valid -> {
                val now = clock.now()
                store.save(StoredLicense(trimmed, now))
                Result.Activated(ActiveLicense(check.payload.licenseIdText, check.payload.maxDevices, now))
            }
            else -> Result.Rejected(check)
        }
    }
}
