package com.eggvyapar.licensetool

import com.eggbusinessmanager.domain.license.LicenseCheck
import com.eggbusinessmanager.domain.license.LicenseCrypto
import com.eggbusinessmanager.domain.license.LicenseFormat
import com.eggbusinessmanager.domain.license.LicensePublicKey
import com.eggbusinessmanager.domain.license.LicenseVerifier
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The owner's license tool: signing key, registry, device limits, export, and keys the app accepts. */
class LicenseToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val home by lazy { LicenseHome(tmp.newFolder("home")).apply { init() } }
    private val registry by lazy { LicenseRegistry(home) }
    private val phoneA = ByteArray(LicenseFormat.DEVICE_HASH_BYTES) { 1 }
    private val phoneB = ByteArray(LicenseFormat.DEVICE_HASH_BYTES) { 2 }
    private val codeA = LicenseFormat.deviceCode(phoneA)
    private val codeB = LicenseFormat.deviceCode(phoneB)

    private fun verifier() = LicenseVerifier(LicensePublicKey(LicenseCrypto.publicKey(home.publicKey())))

    @Test
    fun `issued keys are accepted by the app's verifier only on their own phone`() {
        val license = registry.create("Ramesh Patil", "Patil Eggs")
        assertThat(license.issued).isFalse()
        val device = registry.issue(license.id, codeA.lowercase())

        val check = verifier().check(device.key, phoneA) as LicenseCheck.Valid
        assertThat(check.payload.licenseIdText).isEqualTo(license.id)
        assertThat(check.payload.maxDevices).isEqualTo(1)
        assertThat(verifier().check(device.key, phoneB)).isEqualTo(LicenseCheck.OtherDevice)
        assertThat(registry.get(license.id).issued).isTrue()
    }

    @Test
    fun `the device limit is enforced, the same phone gets its key again, and the limit can be raised`() {
        val license = registry.create("Sita", "Sita Stores", maxDevices = 1)
        val first = registry.issue(license.id, codeA)
        assertThat(registry.issue(license.id, codeA)).isEqualTo(first)
        val refused = assertThrows(LicenseToolException::class.java) { registry.issue(license.id, codeB) }
        assertThat(refused.message).contains("1 of 1")

        registry.setLimit(license.id, 2)
        val second = registry.issue(license.id, codeB)
        assertThat(verifier().check(second.key, phoneB)).isInstanceOf(LicenseCheck.Valid::class.java)
        assertThat(registry.get(license.id).devices.map { it.deviceCode }).containsExactly(codeA, codeB).inOrder()
        assertThrows(LicenseToolException::class.java) { registry.setLimit(license.id, 1) } // below devices issued
    }

    @Test
    fun `license ids are random and unique, and bad input is explained`() {
        val ids = (1..50).map { registry.create("Customer $it", "Shop $it").id }
        assertThat(ids.toSet()).hasSize(50)
        assertThat(ids.all { it.matches(Regex("L-[0-9A-Z]{8}")) }).isTrue()
        assertThrows(LicenseToolException::class.java) { registry.create(" ", "Shop") }
        assertThrows(LicenseToolException::class.java) { registry.create("A", "B", maxDevices = 0) }
        assertThrows(LicenseToolException::class.java) { registry.issue(ids[0], "not-a-code") }
        assertThrows(LicenseToolException::class.java) { registry.issue("L-ZZZZZZZZ", codeA) }
    }

    @Test
    fun `the signing key is created once and never replaced`() {
        val public = home.publicKey()
        assertThrows(LicenseToolException::class.java) { home.init() }
        assertThat(home.publicKey()).isEqualTo(public)
        assertThrows(LicenseToolException::class.java) { LicenseHome(tmp.newFolder("empty")).signingKey() }
    }

    @Test
    fun `export lists every license and device as Excel-safe CSV`() {
        val license = registry.create("Ram, \"Senior\"", "=Ram Eggs", maxDevices = 2, phone = "9876543210")
        registry.issue(license.id, codeA)
        registry.create("Unissued", "Shop")
        val csv = registry.exportCsv().split("\r\n")
        assertThat(csv[0]).startsWith("License ID,Customer,Shop")
        assertThat(csv[1]).contains("\"Ram, \"\"Senior\"\"\"")
        assertThat(csv[1]).contains(",'=Ram Eggs,")
        assertThat(csv[1]).contains(codeA)
        assertThat(csv[2]).contains("Unissued")
        assertThat(csv[2]).contains(",No,")
    }

    @Test
    fun `the command line sells a license in one step and refuses unknown commands`() {
        val lines = mutableListOf<String>()
        run(listOf("sell", "--customer", "Asha", "--shop", "Asha Eggs", "--device", codeA, "--home", home.dir.path), lines::add)
        val key = lines.last()
        assertThat(key).startsWith("EV1-")
        assertThat(verifier().check(key, phoneA)).isInstanceOf(LicenseCheck.Valid::class.java)

        lines.clear()
        run(listOf("list", "--home", home.dir.path), lines::add)
        assertThat(lines.single()).contains("Asha Eggs")
        assertThrows(LicenseToolException::class.java) { run(listOf("frobnicate", "--home", home.dir.path)) {} }
    }
}
