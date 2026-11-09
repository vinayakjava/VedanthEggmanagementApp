package com.eggbusinessmanager.domain.license

import com.eggbusinessmanager.testing.FakeDeviceIdentity
import com.eggbusinessmanager.testing.FakeLicenseStore
import com.eggbusinessmanager.testing.FixedClock
import com.eggbusinessmanager.testing.TestLicenseKeys
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/** Lifetime license keys: format, ECDSA P-256 signatures, device binding and activation. */
class LicenseTest {

    private val phone = ByteArray(LicenseFormat.DEVICE_HASH_BYTES) { (it * 7).toByte() }
    private val otherPhone = ByteArray(LicenseFormat.DEVICE_HASH_BYTES) { (it * 7 + 1).toByte() }
    private val verifier = LicenseVerifier(TestLicenseKeys.publicKey)

    @Test
    fun `a key signed for this phone is valid and carries its id and device limit`() {
        val key = TestLicenseKeys.keyFor(phone, maxDevices = 3)
        assertThat(key).startsWith("EV1-")
        val check = verifier.check(key, phone) as LicenseCheck.Valid
        assertThat(check.payload.maxDevices).isEqualTo(3)
        assertThat(check.payload.licenseIdText).isEqualTo(LicenseFormat.licenseIdText(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun `pasting forgives case, spaces, line breaks and look-alike letters`() {
        val key = TestLicenseKeys.keyFor(phone)
        val messy = "  " + key.lowercase().replace("-", " ").chunked(30).joinToString("\n") + "\n"
        assertThat(verifier.check(messy, phone)).isInstanceOf(LicenseCheck.Valid::class.java)
        val code = LicenseFormat.deviceCode(phone)
        assertThat(code).matches("[0-9A-Z]{4}(-[0-9A-Z]{4}){3}")
        assertThat(LicenseFormat.parseDeviceCode(code.lowercase().replace('0', 'o'))).isEqualTo(phone)
    }

    @Test
    fun `a key for another phone, an altered key, a cut-off key or another owner's key is refused`() {
        val key = TestLicenseKeys.keyFor(phone)
        assertThat(verifier.check(key, otherPhone)).isEqualTo(LicenseCheck.OtherDevice)

        // Raise the device limit inside the key: the signature no longer matches.
        val decoded = LicenseFormat.decodeKey(key)!!
        val forged = LicenseFormat.encodeKey(decoded.payload.copy(maxDevices = 99), decoded.signature)
        assertThat(verifier.check(forged, phone)).isEqualTo(LicenseCheck.NotGenuine)

        assertThat(verifier.check(key.dropLast(7), phone)).isEqualTo(LicenseCheck.Malformed)
        assertThat(verifier.check("hello", phone)).isEqualTo(LicenseCheck.Malformed)

        val stranger = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        assertThat(LicenseVerifier(LicensePublicKey(stranger.public)).check(key, phone)).isEqualTo(LicenseCheck.NotGenuine)
    }

    @Test
    fun `signatures survive the compact raw form for every key`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        repeat(200) { i ->
            val data = "message $i".toByteArray()
            val raw = LicenseCrypto.sign(pair.private, data)
            assertThat(raw).hasLength(LicenseFormat.SIGNATURE_BYTES)
            assertThat(LicenseCrypto.verify(pair.public, data, raw)).isTrue()
            assertThat(LicenseCrypto.verify(pair.public, "other".toByteArray(), raw)).isFalse()
        }
        assertThat(LicenseCrypto.verify(pair.public, byteArrayOf(1), ByteArray(64))).isFalse()
    }

    @Test
    fun `license ids and device codes round-trip`() {
        val id = byteArrayOf(9, 8, 7, 6, 5)
        assertThat(LicenseFormat.parseLicenseId(LicenseFormat.licenseIdText(id))).isEqualTo(id)
        assertThat(LicenseFormat.parseLicenseId("L-??")).isNull()
        assertThat(LicenseFormat.parseDeviceCode("ABCD")).isNull()
        assertThat(LicenseFormat.deviceHash(byteArrayOf(1, 2, 3))).hasLength(LicenseFormat.DEVICE_HASH_BYTES)
    }

    // ---- Activation ----

    private val clock = FixedClock()
    private val identity = FakeDeviceIdentity(phone)
    private val store = FakeLicenseStore()
    private val activate = ActivateLicenseUseCase(store, identity, verifier, clock)
    private val check = CheckLicenseUseCase(store, identity, verifier)

    @Test
    fun `activating saves the key once and every later start is activated without asking`() = runTest {
        assertThat(check()).isEqualTo(LicenseStatus.NotActivated)
        assertThat(GetDeviceCodeUseCase(identity)()).isEqualTo(LicenseFormat.deviceCode(phone))

        val result = activate("  " + TestLicenseKeys.keyFor(phone, maxDevices = 2) + "\n") as ActivateLicenseUseCase.Result.Activated
        assertThat(result.license.maxDevices).isEqualTo(2)
        assertThat(store.saved!!.activatedAt).isEqualTo(clock.now())

        val status = check() as LicenseStatus.Activated
        assertThat(status.license.licenseId).isEqualTo(result.license.licenseId)
    }

    @Test
    fun `a refused key saves nothing and a phone whose identity changed is asked again`() = runTest {
        val rejected = activate(TestLicenseKeys.keyFor(otherPhone)) as ActivateLicenseUseCase.Result.Rejected
        assertThat(rejected.reason).isEqualTo(LicenseCheck.OtherDevice)
        assertThat(store.saved).isNull()

        activate(TestLicenseKeys.keyFor(phone))
        identity.hash = otherPhone // e.g. the data was moved to a new phone
        assertThat(check()).isEqualTo(LicenseStatus.NotActivated)
        assertThat(store.saved).isNotNull() // nothing is deleted
    }
}
