package com.eggbusinessmanager.testing

import com.eggbusinessmanager.domain.license.DeviceIdentity
import com.eggbusinessmanager.domain.license.LicenseCrypto
import com.eggbusinessmanager.domain.license.LicenseFormat
import com.eggbusinessmanager.domain.license.LicensePublicKey
import com.eggbusinessmanager.domain.license.LicenseStore
import com.eggbusinessmanager.domain.license.StoredLicense

/**
 * A throwaway key pair for tests and debug builds only. Debug builds embed [PUBLIC]; the release
 * build embeds the owner's real public key, so keys signed with this test key never work there.
 */
object TestLicenseKeys {
    const val PUBLIC =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEP3n+EoAO50SUF/1nOw+kiwB8IukQDS6u29AkFyhzDAWSlH36OSrqIQK/pG4/GndcDbqDTE6ErC5ZzlRRVrzhbg=="
    private const val PRIVATE =
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgoGUXnyRIj120fNJIoubG+8A+HD/Fuzu3gULHoq/9j2qhRANCAAQ/ef4SgA7nRJQX/Wc7D6SL" +
            "AHwi6RANLq7b0CQXKHMMBZKUffo5KuohAr+kbj8ad1wNuoNMToSsLlnOVFFWvOFu"

    val publicKey: LicensePublicKey get() = LicensePublicKey(LicenseCrypto.publicKey(PUBLIC))

    /** A key for [deviceHash], signed with the test key. */
    fun keyFor(deviceHash: ByteArray, maxDevices: Int = 1, licenseId: ByteArray = byteArrayOf(1, 2, 3, 4, 5)): String {
        val payload = LicenseFormat.Payload(licenseId, maxDevices, deviceHash)
        val signature = LicenseCrypto.sign(LicenseCrypto.privateKey(PRIVATE), LicenseFormat.signedBytes(payload))
        return LicenseFormat.encodeKey(payload, signature)
    }
}

class FakeDeviceIdentity(var hash: ByteArray = ByteArray(LicenseFormat.DEVICE_HASH_BYTES) { it.toByte() }) : DeviceIdentity {
    override suspend fun deviceHash(): ByteArray = hash
}

class FakeLicenseStore(var saved: StoredLicense? = null) : LicenseStore {
    override suspend fun load(): StoredLicense? = saved

    override suspend fun save(license: StoredLicense) {
        saved = license
    }
}
